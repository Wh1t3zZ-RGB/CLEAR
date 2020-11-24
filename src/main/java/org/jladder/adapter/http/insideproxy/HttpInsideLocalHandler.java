package org.jladder.adapter.http.insideproxy;

import org.jladder.adapter.http.wrapper.HttpRequestWrapper;
import org.jladder.adapter.http.wrapper.HttpRequestWrapperHandler;
import org.jladder.adapter.protocol.JladderByteBuf;
import org.jladder.adapter.protocol.JladderMessage;
import org.jladder.adapter.protocol.JladderMessageReceiveEvent;
import org.jladder.adapter.protocol.executor.JladderForwardExecutor;
import org.jladder.adapter.protocol.listener.JladderOnReceiveDataListener;
import org.jladder.common.Constant;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.util.CharsetUtil;

/**
 * HTTP/HTTPS 加密转发
 * <pre>
 *   针对HTTP请求，需要程序进行加密解密转发；而针对HTTPS请求，加解密由SSL协议完成，因此只需要透传转发。
 * </pre>
 * @author hudaming
 */
@Sharable
public class HttpInsideLocalHandler extends SimpleChannelInboundHandler<HttpRequestWrapper> {

	private static final ByteBuf HTTPS_CONNECTED_LINE = PooledByteBufAllocator.DEFAULT.directBuffer();
	static {
		HTTPS_CONNECTED_LINE.writeBytes(Constant.ConnectedLine.getBytes());
	}
	private final static JladderForwardExecutor JladderForwardExecutor = new JladderForwardExecutor();
	
	@Override
	protected void channelRead0(ChannelHandlerContext browserCtx, HttpRequestWrapper requestWrapper) throws Exception {

		if (requestWrapper.host() == null || requestWrapper.host().isEmpty()) {
			/**
			 * 这里不要close，否则用Chrome访问news.baidu.com会导致EmptyResponse
			 * 在调试时发现，decode第一个请求正常，但第二个请求则不是一个正常的http请求，此时disscard比close更有利于后面处理
			 */
			// browserCtx.close(); 
			return;
		}
		
		// 转发前记录真实IP，防止转发中丢失源IP地址
		requestWrapper.header("x-forwarded-for", browserCtx.channel().remoteAddress().toString());
		
		if (requestWrapper.isHttps()) {
			browserCtx.pipeline().remove(this);
			browserCtx.pipeline().remove(io.netty.handler.codec.http.HttpRequestDecoder.class);
			browserCtx.pipeline().remove(HttpObjectAggregator.class);
			browserCtx.pipeline().remove(HttpRequestWrapperHandler.class);
			browserCtx.pipeline().addLast(new SimpleForwardChannelHandler(requestWrapper.host(), requestWrapper.port()));
			browserCtx.writeAndFlush(HTTPS_CONNECTED_LINE);
			System.out.println("https flush connected-line");
			return ;
		} else {
			JladderOnReceiveDataListener receiveListener = JladderForwardExecutor.writeAndFlush(JladderMessage.buildNeedEncryptMessage(requestWrapper.host(), requestWrapper.port(), requestWrapper.toByteBuf()));
			receiveListener.onReceive(new JladderMessageReceiveEvent() {
				@Override
				public void onReceive(JladderByteBuf byteBuf) {
					System.out.println(byteBuf.toByteBuf().toString(CharsetUtil.UTF_8));
					browserCtx.writeAndFlush(byteBuf.toByteBuf());
				}
			});
		}
	}
	
	private static class SimpleForwardChannelHandler extends ChannelInboundHandlerAdapter {
		
		private String remoteHost;
		private int remotePort;
		
		public SimpleForwardChannelHandler(String host, int port) {
			this.remoteHost = host;
			this.remotePort = port;
		}

	    @Override
	    public void channelRead(ChannelHandlerContext browserCtx, Object msg) throws Exception {
	    	System.out.println("https read browser request");
	    	if (msg instanceof ByteBuf) {
	    		JladderOnReceiveDataListener receiveListener = JladderForwardExecutor.writeAndFlush(JladderMessage.buildUnNeedEncryptMessage(remoteHost, remotePort, (ByteBuf) msg));
				System.out.println("https flush request to remote");
	    		receiveListener.onReceive(new JladderMessageReceiveEvent() {
	    			@Override
	    			public void onReceive(JladderByteBuf byteBuf) {
	    				System.out.println("https flush request to browser");
	    				browserCtx.writeAndFlush(byteBuf.toByteBuf());
	    			}
	    		});
	    	}
	    	browserCtx.fireChannelRead(msg);
	    }
	}
}