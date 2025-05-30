/*
 * Copyright 2018 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */

package com.netflix.zuul.netty.server;

import static com.netflix.netty.common.HttpLifecycleChannelHandler.CompleteEvent;
import static com.netflix.netty.common.HttpLifecycleChannelHandler.CompleteReason;
import com.netflix.netty.common.SourceAddressChannelHandler;
import com.netflix.netty.common.ssl.SslHandshakeInfo;
import com.netflix.netty.common.throttle.RejectionUtils;
import com.netflix.spectator.api.Spectator;
import com.netflix.zuul.context.CommonContextKeys;
import com.netflix.zuul.context.Debug;
import com.netflix.zuul.context.SessionContext;
import com.netflix.zuul.context.SessionContextDecorator;
import com.netflix.zuul.exception.ZuulException;
import com.netflix.zuul.message.Headers;
import com.netflix.zuul.message.http.HttpQueryParams;
import com.netflix.zuul.message.http.HttpRequestMessage;
import com.netflix.zuul.message.http.HttpRequestMessageImpl;
import com.netflix.zuul.message.http.HttpResponseMessage;
import com.netflix.zuul.netty.ChannelUtils;
import com.netflix.zuul.netty.server.http2.Http2OrHttpHandler;
import com.netflix.zuul.netty.server.ssl.SslHandshakeInfoHandler;
import com.netflix.zuul.passport.CurrentPassport;
import com.netflix.zuul.passport.PassportState;
import com.netflix.zuul.stats.status.StatusCategoryUtils;
import com.netflix.zuul.stats.status.ZuulStatusCategory;
import com.netflix.zuul.util.HttpUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.unix.Errors;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.perfmark.PerfMark;
import io.perfmark.TaskCloseable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by saroskar on 1/6/17.
 */
public class ClientRequestReceiver extends ChannelDuplexHandler {

    public static final AttributeKey<HttpRequestMessage> ATTR_ZUUL_REQ = AttributeKey.newInstance("_zuul_request");
    public static final AttributeKey<HttpResponseMessage> ATTR_ZUUL_RESP = AttributeKey.newInstance("_zuul_response");
    public static final AttributeKey<Boolean> ATTR_LAST_CONTENT_RECEIVED =
            AttributeKey.newInstance("_last_content_received");

    private static final Logger LOG = LoggerFactory.getLogger(ClientRequestReceiver.class);
    private static final String SCHEME_HTTP = "http";
    private static final String SCHEME_HTTPS = "https";

    // via @stephenhay https://mathiasbynens.be/demo/url-regex, groups added
    // group 1: scheme, group 2: domain, group 3: path+query
    private static final Pattern URL_REGEX = Pattern.compile("^(https?)://([^\\s/$.?#].[^\\s/]*)([^\\s]*)$");

    private final SessionContextDecorator decorator;

    private HttpRequestMessage zuulRequest;
    private HttpRequest clientRequest;

    public ClientRequestReceiver(SessionContextDecorator decorator) {
        this.decorator = decorator;
    }

    public static HttpRequestMessage getRequestFromChannel(Channel ch) {
        return ch.attr(ATTR_ZUUL_REQ).get();
    }

    public static HttpResponseMessage getResponseFromChannel(Channel ch) {
        return ch.attr(ATTR_ZUUL_RESP).get();
    }

    public static boolean isLastContentReceivedForChannel(Channel ch) {
        Boolean value = ch.attr(ATTR_LAST_CONTENT_RECEIVED).get();
        return value == null ? false : value;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try (TaskCloseable ignore = PerfMark.traceTask("CRR.channelRead")) {
            channelReadInternal(ctx, msg);
        }
    }

    private void channelReadInternal(ChannelHandlerContext ctx, Object msg) {
        // Flag that we have now received the LastContent for this request from the client.
        // This is needed for ClientResponseReceiver to know whether it's yet safe to start writing
        // a response to the client channel.
        if (msg instanceof LastHttpContent) {
            ctx.channel().attr(ATTR_LAST_CONTENT_RECEIVED).set(Boolean.TRUE);
        }

        if (msg instanceof HttpRequest) {
            clientRequest = (HttpRequest) msg;

            zuulRequest = buildZuulHttpRequest(clientRequest, ctx);

            // Handle invalid HTTP requests.
            if (clientRequest.decoderResult().isFailure()) {
                LOG.warn(
                        "Invalid http request. clientRequest = {} , uri = {}, info = {}",
                        clientRequest,
                        clientRequest.uri(),
                        ChannelUtils.channelInfoForLogging(ctx.channel()),
                        clientRequest.decoderResult().cause());
                StatusCategoryUtils.setStatusCategory(
                        zuulRequest.getContext(),
                        ZuulStatusCategory.FAILURE_CLIENT_BAD_REQUEST,
                        "Invalid request provided: Decode failure");
                RejectionUtils.rejectByClosingConnection(
                        ctx,
                        ZuulStatusCategory.FAILURE_CLIENT_BAD_REQUEST,
                        "decodefailure",
                        clientRequest,
                        /* injectedLatencyMillis= */ null);
                return;
            } else if (zuulRequest.hasBody() && zuulRequest.getBodyLength() > zuulRequest.getMaxBodySize()) {
                String errorMsg = "Request too large. "
                        + "clientRequest = " + clientRequest.toString()
                        + ", uri = " + String.valueOf(clientRequest.uri())
                        + ", info = " + ChannelUtils.channelInfoForLogging(ctx.channel());
                ZuulException ze = new ZuulException(errorMsg);
                ze.setStatusCode(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE.code());
                StatusCategoryUtils.setStatusCategory(
                        zuulRequest.getContext(),
                        ZuulStatusCategory.FAILURE_CLIENT_BAD_REQUEST,
                        "Invalid request provided: Request body size " + zuulRequest.getBodyLength()
                                + " is above limit of " + zuulRequest.getMaxBodySize());
                zuulRequest.getContext().setError(ze);
                zuulRequest.getContext().setShouldSendErrorResponse(true);
            } else if (zuulRequest
                            .getHeaders()
                            .getAll(HttpHeaderNames.HOST.toString())
                            .size()
                    > 1) {
                LOG.debug(
                        "Multiple Host headers. clientRequest = {} , uri = {}, info = {}",
                        clientRequest,
                        clientRequest.uri(),
                        ChannelUtils.channelInfoForLogging(ctx.channel()));
                ZuulException ze = new ZuulException("Multiple Host headers");
                ze.setStatusCode(HttpResponseStatus.BAD_REQUEST.code());
                StatusCategoryUtils.setStatusCategory(
                        zuulRequest.getContext(),
                        ZuulStatusCategory.FAILURE_CLIENT_BAD_REQUEST,
                        "Invalid request provided: Multiple Host headers");
                zuulRequest.getContext().setError(ze);
                zuulRequest.getContext().setShouldSendErrorResponse(true);
            }

            handleExpect100Continue(ctx, clientRequest);

            // Send the request down the filter pipeline
            ctx.fireChannelRead(zuulRequest);
        } else if (msg instanceof HttpContent) {
            if ((zuulRequest != null) && !zuulRequest.getContext().isCancelled()) {
                ctx.fireChannelRead(msg);
            } else {
                // We already sent response for this request, these are laggard request body chunks that are still
                // arriving
                ReferenceCountUtil.release(msg);
            }
        } else if (msg instanceof HAProxyMessage) {
            // do nothing, should already be handled by ElbProxyProtocolHandler
            LOG.debug("Received HAProxyMessage for Proxy Protocol IP: {}", ((HAProxyMessage) msg).sourceAddress());
            ReferenceCountUtil.release(msg);
        } else {
            LOG.debug("Received unrecognized message type. {}", msg.getClass().getName());
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof CompleteEvent) {
            CompleteReason reason = ((CompleteEvent) evt).getReason();
            if (zuulRequest != null) {
                zuulRequest.getContext().cancel();
                zuulRequest.disposeBufferedBody();
                CurrentPassport passport = CurrentPassport.fromSessionContext(zuulRequest.getContext());
                if ((passport != null) && (passport.findState(PassportState.OUT_RESP_LAST_CONTENT_SENT) == null)) {
                    // Only log this state if the response does not seem to have completed normally.
                    passport.add(PassportState.IN_REQ_CANCELLED);
                }
            }

            if (reason == CompleteReason.INACTIVE && zuulRequest != null) {
                // Client closed connection prematurely.
                StatusCategoryUtils.setStatusCategory(
                        zuulRequest.getContext(), ZuulStatusCategory.FAILURE_CLIENT_CANCELLED);
            }

            if (reason == CompleteReason.PIPELINE_REJECT && zuulRequest != null) {
                StatusCategoryUtils.setStatusCategory(
                        zuulRequest.getContext(), ZuulStatusCategory.FAILURE_CLIENT_PIPELINE_REJECT);
            }

            if (reason != CompleteReason.SESSION_COMPLETE && zuulRequest != null) {
                SessionContext zuulCtx = zuulRequest.getContext();
                if (clientRequest != null) {
                    if (LOG.isInfoEnabled()) {
                        // With http/2, the netty codec closes/completes the stream immediately after writing the
                        // lastcontent
                        // of response to the channel, which causes this CompleteEvent to fire before we have cleaned up
                        // state. But
                        // thats ok, so don't log in that case.
                        if (Objects.equals(zuulRequest.getProtocol(), "HTTP/2")) {
                            LOG.debug(
                                    "Client {} request UUID {} to {} completed with reason = {}, {}",
                                    clientRequest.method(),
                                    zuulCtx.getUUID(),
                                    clientRequest.uri(),
                                    reason.name(),
                                    ChannelUtils.channelInfoForLogging(ctx.channel()));
                        }
                    }
                }
                if (zuulCtx.debugRequest()) {
                    LOG.debug("Endpoint = {}", zuulCtx.getEndpoint());
                    dumpDebugInfo(Debug.getRequestDebug(zuulCtx));
                    dumpDebugInfo(Debug.getRoutingDebug(zuulCtx));
                }
            }

            if (zuulRequest == null) {
                Spectator.globalRegistry()
                        .counter("zuul.client.complete.null", "reason", String.valueOf(reason))
                        .increment();
            }

            clientRequest = null;
            zuulRequest = null;
        }

        super.userEventTriggered(ctx, evt);

        if (evt instanceof CompleteEvent) {
            Channel channel = ctx.channel();
            channel.attr(ATTR_ZUUL_REQ).set(null);
            channel.attr(ATTR_ZUUL_RESP).set(null);
            channel.attr(ATTR_LAST_CONTENT_RECEIVED).set(null);
        }
    }

    private static void dumpDebugInfo(List<String> debugInfo) {
        debugInfo.forEach((dbg) -> LOG.debug(dbg));
    }

    private void handleExpect100Continue(ChannelHandlerContext ctx, HttpRequest req) {
        if (HttpUtil.is100ContinueExpected(req)) {
            PerfMark.event("CRR.handleExpect100Continue");
            ChannelFuture f =
                    ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
            f.addListener((s) -> {
                if (!s.isSuccess()) {
                    throw new ZuulException(s.cause(), "Failed while writing 100-continue response", true);
                }
            });
            // Remove the Expect: 100-Continue header from request as we don't want to proxy it downstream.
            req.headers().remove(HttpHeaderNames.EXPECT);
            zuulRequest.getHeaders().remove(HttpHeaderNames.EXPECT.toString());
        }
    }

    // Build a ZuulMessage from the netty request.
    private HttpRequestMessage buildZuulHttpRequest(HttpRequest nativeRequest, ChannelHandlerContext clientCtx) {
        PerfMark.attachTag("path", nativeRequest, HttpRequest::uri);
        // Setup the context for this request.
        SessionContext context;
        if (decorator != null) { // Optionally decorate the context.
            SessionContext tempContext = new SessionContext();
            // Store the netty channel in SessionContext.
            tempContext.set(CommonContextKeys.NETTY_SERVER_CHANNEL_HANDLER_CONTEXT, clientCtx);
            context = decorator.decorate(tempContext);
            // We expect the UUID is present after decoration
            PerfMark.attachTag("uuid", context, SessionContext::getUUID);
        } else {
            context = new SessionContext();
        }

        // Get the client IP (ignore XFF headers at this point, as that can be app specific).
        Channel channel = clientCtx.channel();
        String clientIp = getClientIp(channel);

        // This is the only way I found to get the port of the request with netty...
        int port =
                channel.attr(SourceAddressChannelHandler.ATTR_SERVER_LOCAL_PORT).get();
        String serverName = channel.attr(SourceAddressChannelHandler.ATTR_SERVER_LOCAL_ADDRESS)
                .get();
        SocketAddress clientDestinationAddress =
                channel.attr(SourceAddressChannelHandler.ATTR_LOCAL_ADDR).get();
        InetSocketAddress proxyProtocolDestinationAddress = channel.attr(
                        SourceAddressChannelHandler.ATTR_PROXY_PROTOCOL_DESTINATION_ADDRESS)
                .get();
        if (proxyProtocolDestinationAddress != null) {
            context.set(CommonContextKeys.PROXY_PROTOCOL_DESTINATION_ADDRESS, proxyProtocolDestinationAddress);
        }

        // Store info about the SSL handshake if applicable, and choose the http scheme.
        String scheme = SCHEME_HTTP;
        SslHandshakeInfo sslHandshakeInfo =
                channel.attr(SslHandshakeInfoHandler.ATTR_SSL_INFO).get();
        if (sslHandshakeInfo != null) {
            context.set(CommonContextKeys.SSL_HANDSHAKE_INFO, sslHandshakeInfo);
            scheme = SCHEME_HTTPS;
        }

        // Decide if this is HTTP/1 or HTTP/2.
        String protocol = channel.attr(Http2OrHttpHandler.PROTOCOL_NAME).get();
        if (protocol == null) {
            protocol = nativeRequest.protocolVersion().text();
        }

        // Strip off the query from the path.
        String path = parsePath(nativeRequest.uri());

        // Setup the req/resp message objects.
        HttpRequestMessage request = new HttpRequestMessageImpl(
                context,
                protocol,
                nativeRequest.method().asciiName().toString().toLowerCase(Locale.ROOT),
                path,
                copyQueryParams(nativeRequest),
                copyHeaders(nativeRequest),
                clientIp,
                scheme,
                port,
                serverName,
                clientDestinationAddress,
                false);

        // Try to decide if this request has a body or not based on the headers (as we won't yet have
        // received any of the content).
        // NOTE that we also later may override this if it is Chunked encoding, but we receive
        // a LastHttpContent without any prior HttpContent's.
        if (HttpUtils.hasChunkedTransferEncodingHeader(request) || HttpUtils.hasNonZeroContentLengthHeader(request)) {
            request.setHasBody(true);
        }

        // Store this original request info for future reference (ie. for metrics and access logging purposes).
        request.storeInboundRequest();

        // Store the netty request for use later.
        context.set(CommonContextKeys.NETTY_HTTP_REQUEST, nativeRequest);

        // Store zuul request on netty channel for later use.
        channel.attr(ATTR_ZUUL_REQ).set(request);

        if (nativeRequest instanceof DefaultFullHttpRequest) {
            ByteBuf chunk = ((DefaultFullHttpRequest) nativeRequest).content();
            request.bufferBodyContents(new DefaultLastHttpContent(chunk));
        }

        return request;
    }

    protected String getClientIp(Channel channel) {
        return channel.attr(SourceAddressChannelHandler.ATTR_SOURCE_ADDRESS).get();
    }

    private String parsePath(String uri) {
        String path;
        // relative uri
        if (uri.startsWith("/")) {
            path = uri;
        } else {
            Matcher m = URL_REGEX.matcher(uri);

            // absolute uri
            if (m.matches()) {
                String match = m.group(3);
                if (match == null) {
                    // in case of no match, default to existing behavior
                    path = uri;
                } else {
                    path = match;
                }
            }
            // unknown value
            else {
                // in case of unknown value, default to existing behavior
                path = uri;
            }
        }

        int queryIndex = path.indexOf('?');
        if (queryIndex > -1) {
            return path.substring(0, queryIndex);
        } else {
            return path;
        }
    }

    private static Headers copyHeaders(HttpRequest req) {
        Headers headers = new Headers(req.headers().size());
        for (Iterator<Entry<String, String>> it = req.headers().iteratorAsString(); it.hasNext(); ) {
            Entry<String, String> header = it.next();
            headers.add(header.getKey(), header.getValue());
        }
        return headers;
    }

    public static HttpQueryParams copyQueryParams(HttpRequest nativeRequest) {
        String uri = nativeRequest.uri();
        int queryStart = uri.indexOf('?');
        String query = queryStart == -1 ? null : uri.substring(queryStart + 1);
        return HttpQueryParams.parse(query);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        try (TaskCloseable ignored = PerfMark.traceTask("CRR.write")) {
            if (msg instanceof HttpResponse) {
                promise.addListener((future) -> {
                    if (!future.isSuccess()) {
                        fireWriteError("response headers", future.cause(), ctx);
                    }
                });
                super.write(ctx, msg, promise);
            } else if (msg instanceof HttpContent) {
                promise.addListener((future) -> {
                    if (!future.isSuccess()) {
                        fireWriteError("response content", future.cause(), ctx);
                    }
                });
                super.write(ctx, msg, promise);
            } else {
                // should never happen
                ReferenceCountUtil.release(msg);
                throw new ZuulException(
                        "Attempt to write invalid content type to client: "
                                + msg.getClass().getSimpleName(),
                        true);
            }
        }
    }

    private void fireWriteError(String requestPart, Throwable cause, ChannelHandlerContext ctx) {

        String errMesg = String.format("Error writing %s to client", requestPart);

        if (cause instanceof java.nio.channels.ClosedChannelException
                || cause instanceof Errors.NativeIoException
                || cause instanceof SSLException
                || (cause.getCause() != null && cause.getCause() instanceof SSLException)
                || isStreamCancelled(cause)) {
            LOG.debug("{} - client connection is closed.", errMesg);
            if (zuulRequest != null) {
                zuulRequest.getContext().cancel();
                StatusCategoryUtils.storeStatusCategoryIfNotAlreadyFailure(
                        zuulRequest.getContext(), ZuulStatusCategory.FAILURE_CLIENT_CANCELLED);
            }
        } else {
            LOG.error(errMesg, cause);
            ctx.fireExceptionCaught(new ZuulException(cause, errMesg, true));
        }
    }

    private boolean isStreamCancelled(Throwable cause) {
        // Detect if the stream is cancelled or closed.
        // If the stream was closed before the write occurred, then netty flags it with INTERNAL_ERROR code.
        if (cause instanceof Http2Exception.StreamException) {
            Http2Exception http2Exception = (Http2Exception) cause;
            if (http2Exception.error() == Http2Error.INTERNAL_ERROR) {
                return true;
            }
        }
        return false;
    }
}
