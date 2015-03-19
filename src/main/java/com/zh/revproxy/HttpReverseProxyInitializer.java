package com.zh.revproxy;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslHandler;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

public class HttpReverseProxyInitializer extends ChannelInitializer<SocketChannel> {
	private final boolean isSecuredProxy;
	private final boolean isSecureBackend;
	
	private final String keyStoreLocation;
	private final String keyStorePassword;
	
	private final String trustStoreLocation;
	private final String trustStorePassword;
	
	public HttpReverseProxyInitializer(boolean isSecuredProxy, String keyStoreLocation, String keyStorePassword, boolean isSecuredBackend,
		String trustStoreLocation, String trustStorePasswprd) {
		this.isSecuredProxy = isSecuredProxy;
		this.keyStoreLocation = keyStoreLocation;
		this.keyStorePassword = keyStorePassword;
		this.isSecureBackend = isSecuredBackend;
		this.trustStoreLocation = trustStoreLocation;
		this.trustStorePassword = trustStorePasswprd;
	}
	
	@Override
	protected void initChannel(SocketChannel ch) throws Exception {
		ChannelPipeline pipeline = ch.pipeline();
		
		if (isSecuredProxy) {
			/*
			 * Sets a secured channel between the client and the reverse proxy
			 * service. This is a Server SSL context since proxy is acting as a
			 * server for this connection..
			 */
			// pipeline.addLast(sslContext.newHandler(ch.alloc()));
			
			SSLContext serverSSLContext = SSLUtil.createServerSSLContext(keyStoreLocation, keyStorePassword);
			SSLEngine sslEngine = serverSSLContext.createSSLEngine();
			sslEngine.setUseClientMode(false);
			pipeline.addLast("ssl", new SslHandler(sslEngine));
		}
		
		// needed to decode and analyze the http headers and other 
		// load balancing params
		// XXX: 1 MB for headers is enough , I think // VM
		pipeline.addLast(new HttpServerCodec(1048576, 1048576, 1048576));
		pipeline.addLast(new HttpObjectAggregator(104857600));
		pipeline.addLast(new HttpReverseProxyFrontendHandler(isSecureBackend, trustStoreLocation, trustStorePassword));
	}
}
