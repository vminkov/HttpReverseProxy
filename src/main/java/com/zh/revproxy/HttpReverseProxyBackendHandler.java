package com.zh.revproxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultLastHttpContent;

public class HttpReverseProxyBackendHandler extends ChannelInboundHandlerAdapter {
	private final Channel inboundChannel;
	
	public HttpReverseProxyBackendHandler(Channel inboundChannel) {
		this.inboundChannel = inboundChannel;
	}
	
	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		// start reading the message that comes as response
		// from the backend
		ctx.read();
	}
	
	@Override
	public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
		// forward the message back to the caller
		this.inboundChannel.writeAndFlush(msg).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				if (future.isSuccess()) {
					if (msg instanceof DefaultLastHttpContent) {
						// This was the last message -> close the channel
						ctx.channel().close();
					}
				}
				else {
					future.cause().printStackTrace();
					future.channel().close();
				}
			}
		});
	}
	
	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		HttpReverseProxyFrontendHandler.closeOnFlush(ctx.channel());
	}
	
	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		System.err.println("Error while receiving the backend response " + cause.getCause());
		cause.printStackTrace();
		HttpReverseProxyFrontendHandler.closeOnFlush(ctx.channel());
	}
}
