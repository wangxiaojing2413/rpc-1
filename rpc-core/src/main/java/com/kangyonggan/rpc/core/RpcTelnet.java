package com.kangyonggan.rpc.core;

import com.kangyonggan.rpc.handler.RpcTelnetHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.apache.log4j.Logger;

/**
 * @author kangyonggan
 * @since 2019-02-19
 */
public class RpcTelnet extends Thread {

    private Logger logger = Logger.getLogger(RpcTelnet.class);

    private int port;

    public RpcTelnet(int port) {
        this.port = port;
    }

    @Override
    public void run() {
        logger.info("telnet服务治理正在启动...");

        // 接收客户端的链接
        EventLoopGroup bossGroup = new NioEventLoopGroup();

        // 处理已被接收的链接
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap serverBootstrap = new ServerBootstrap();
            serverBootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 换行符分割
                            ch.pipeline().addLast(new DelimiterBasedFrameDecoder(65535, Delimiters.lineDelimiter()));

                            // 解码
                            ch.pipeline().addLast(new StringDecoder());

                            // 编码
                            ch.pipeline().addLast(new StringEncoder());

                            //超时
                            ch.pipeline().addLast(new ReadTimeoutHandler(500));
                            // 收发消息
                            ch.pipeline().addLast(new RpcTelnetHandler(port));
                        }
                    }).option(ChannelOption.SO_BACKLOG, 128).childOption(ChannelOption.SO_KEEPALIVE, true);

            // 绑定端口，开始接收进来的链接
            ChannelFuture channelFuture = serverBootstrap.bind(port).sync();
            logger.info("telnet服务治理启动完成，监听【" + port + "】端口");

            // 等待服务器关闭
            channelFuture.channel().closeFuture().sync();
        } catch (Exception e) {
            logger.error("telnet服务治理启动异常，监听【" + port + "】端口", e);
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}
