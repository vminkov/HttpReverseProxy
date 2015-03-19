package com.zh.revproxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders.Names;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

public class HttpReverseProxyFrontendHandler extends SimpleChannelInboundHandler<Object> {
	private final boolean isSecureBackend;
	private final String trustStoreLocation;
	private final String trustStorePassword;
	
	private static Bootstrap b = new Bootstrap().group(new NioEventLoopGroup()).channel(NioSocketChannel.class);
	
	public HttpReverseProxyFrontendHandler(boolean isSecuredBackend, String trustStoreLocation, String trustStorePassword) {
		this.isSecureBackend = isSecuredBackend;
		this.trustStoreLocation = trustStoreLocation;
		this.trustStorePassword = trustStorePassword;
	}
	
	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		// start reading the client's message
		ctx.read();
	}
	
	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		closeOnFlush(ctx.channel());
	}
	
	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		System.err.println("Error while receiving the frontend request " + cause.getCause());
		cause.printStackTrace();
		closeOnFlush(ctx.channel());
	}
	
	/**
	 * Closes the specified channel after all queued write requests are flushed.
	 */
	static void closeOnFlush(Channel ch) {
		if (ch.isActive()) {
			ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
		}
	}
	
	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		// accepts anything and tries to parse it
		if (this.acceptInboundMessage(msg)) {
			this.channelRead0(ctx, msg);
		}
		else {
			// lets it go to the next on the pipeline
			ctx.fireChannelRead(msg);
		}
	}
	
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
		Channel inboundChannel = ctx.channel();
		
		// the message is expected to be combined
		// into a full message by the aggregator
		if (msg instanceof FullHttpRequest) {
			FullHttpRequest request = (FullHttpRequest) msg;
			
			if (request.getMethod().equals(HttpMethod.OPTIONS) && HttpReverseProxy.AUTO_CORS) {
				// CORS
				this.respondWithCors(inboundChannel, request);
			}
			else {
				// determines which backend should get this message
				ReverseProxyBackend backend = this.getBackendByRequest(request);
				
				//initialize
				final Channel outboundChannel = this.createOutBoundChannel(inboundChannel, backend);
				
				// sends it to the backend
				this.writeAndFlush(inboundChannel, outboundChannel, request);
			}
		}
		else {
			throw new RuntimeException("The message is not valid " + msg.getClass());
		}
	}
	
	private void respondWithCors(Channel inboundChannel, FullHttpRequest request) {
		//		HTTP/1.1 200 OK
		//		Access-Control-Allow-Origin: *
		//		Access-Control-Max-Age: 31536000
		//		Access-Control-Allow-Headers: X-Requested-With,Content-Type,Accept,Origin,Authorization
		//		Access-Control-Allow-Methods: GET,POST,DELETE,PUT,HEAD
		//		Content-Length: 0
		//		Cache-Control: private
		
		inboundChannel.writeAndFlush(corsResponse);
	}
	
	private void respondWithInternalServerError(Channel inboundChannel) {
		inboundChannel.writeAndFlush(internalErrorResponse);
	}
	
	private void respondWithServiceUnavailable(Channel inboundChannel) {
		inboundChannel.writeAndFlush(serviceUnavailableResponse);
	}
	
	private Channel createOutBoundChannel(final Channel inboundChannel, ReverseProxyBackend backend) {
		ChannelFuture connectFuture;
		
		// Start the connection attempt (to the backend)
		synchronized (b) {
			b.handler(new SecureProxyInitializer(inboundChannel, this.isSecureBackend, this.trustStoreLocation, this.trustStorePassword)).option(
				ChannelOption.AUTO_READ, true);
			connectFuture = b.connect(backend.getAddress(), backend.getPort());
		}
		
		final Channel outboundChannel = connectFuture.channel();
		
		connectFuture.addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				if (future.isSuccess()) {
					// let the writing to this channel go
					// when it is active
					synchronized (outboundChannel) {
						outboundChannel.notify();
					}
					
					// connection complete start to read next data
					inboundChannel.read();
				}
				else {
					System.err.println("error while connecting to the backend " + future.cause());
					future.cause().printStackTrace();
					HttpReverseProxyFrontendHandler.this.respondWithServiceUnavailable(inboundChannel);
					// Close the connection if the connection attempt has
					// failed.
					closeOnFlush(inboundChannel);
				}
			}
		});
		
		return outboundChannel;
	}
	
	private ReverseProxyBackend getBackendByRequest(FullHttpRequest request) {
		ReverseProxyBackend backend = null;
		
		for (ReverseProxyFrontendRule rule : HttpReverseProxy.frontendRules) {
			if (rule.matches(request)) {
				backend = rule.getBackend();
				break;
			}
		}
		
		if (backend == null) {
			backend = HttpReverseProxy.defaultBackend;
		}
		
		//		System.out.println("Request with URI " + request.getUri());
		//		System.out.println("with method " + request.getMethod());
		//		System.out.println("matched backend " + backend.getAddress() + ":" + backend.getPort() + "\n\n");
		
		return backend;
	}
	
	private void writeAndFlush(final Channel inboundChannel, final Channel outboundChannel, HttpObject fullRequest) {
		// wait until the channel to the backend is active
		synchronized (outboundChannel) {
			while (!outboundChannel.isActive()) {
				try {
					outboundChannel.wait();
				}
				catch (InterruptedException e) {
					// may be a spurious wake up
					// throw new RuntimeException(e);
				}
			}
		}
		
		// finally forwards the request to the backend
		outboundChannel.writeAndFlush(fullRequest).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture writeFuture) {
				if (writeFuture.isSuccess()) {
					// was able to flush out data, start to read the next
					// chunk
					inboundChannel.read();
				}
				else {
					System.err.println("Error while sending the data to the backend " + writeFuture.cause());
					writeFuture.cause().printStackTrace();
					HttpReverseProxyFrontendHandler.this.respondWithInternalServerError(inboundChannel);
					closeOnFlush(inboundChannel);
				}
			}
		});
	}
	
	private final static FullHttpResponse corsResponse;
	static {
		corsResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		corsResponse.headers().add(Names.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		corsResponse.headers().add(Names.ACCESS_CONTROL_MAX_AGE, 31536000);
		corsResponse.headers().add(Names.ACCESS_CONTROL_ALLOW_HEADERS, "X-Requested-With,Content-Type,Accept,Origin,Authorization");
		corsResponse.headers().add(Names.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,DELETE,PUT,HEAD");
		corsResponse.headers().add(Names.CONTENT_LENGTH, 0);
		corsResponse.headers().add(Names.CACHE_CONTROL, "private");
	}
	
	private final static FullHttpResponse internalErrorResponse;
	static {
		internalErrorResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
		internalErrorResponse.content().writeBytes(HttpResponseStatus.INTERNAL_SERVER_ERROR.toString().getBytes());
		internalErrorResponse.headers().add(Names.CONTENT_LENGTH, HttpResponseStatus.INTERNAL_SERVER_ERROR.toString().length());
	}
	
	private final static FullHttpResponse serviceUnavailableResponse;
	static {
		serviceUnavailableResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SERVICE_UNAVAILABLE);
		serviceUnavailableResponse.content().writeBytes(HttpResponseStatus.SERVICE_UNAVAILABLE.toString().getBytes());
		serviceUnavailableResponse.headers().add(Names.CONTENT_LENGTH, HttpResponseStatus.SERVICE_UNAVAILABLE.toString().length());
	}
}
