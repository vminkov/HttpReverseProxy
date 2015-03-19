package com.zh.revproxy;

public class ReverseProxyBackend {
	private final String address;
	private final int port;
	private final boolean ssl;
	
	public ReverseProxyBackend(String address, int port, boolean ssl) {
		this.address = address;
		this.port = port;
		this.ssl = ssl;
	}
	
	public String getAddress() {
		return address;
	}
	
	public int getPort() {
		return port;
	}
	
	public boolean isSsl() {
		return ssl;
	}
}
