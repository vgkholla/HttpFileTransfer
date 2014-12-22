package io.example.filetransfer;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class FileClient implements Runnable {
  private static final String dir = "/tmp/client/";
  private static final String file_prefix = "x";
  private String path;

  public static void main(String[] args)
      throws Exception {

    File f = new File(dir);
    if (!f.exists()) {
      f.mkdir();
    }

    BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
    System.out.println("Enter number of threads: ");
    int num_threads = Integer.parseInt(in.readLine());

    ExecutorService executor = Executors.newFixedThreadPool(num_threads);

    for (int i = 0; i < num_threads; i++) {
      executor.execute(new FileClient(dir + file_prefix + Integer.toString(i)));
    }

    executor.shutdown();
    executor.awaitTermination(1, TimeUnit.DAYS);
    System.out.println("Done");
  }

  public FileClient(String path) {
    this.path = path;
  }

  public void run() {
    EventLoopGroup group = new NioEventLoopGroup();

    try {
      Bootstrap b = new Bootstrap();
      b.group(group);
      b.channel(NioSocketChannel.class); // (3)
      b.option(ChannelOption.SO_KEEPALIVE, false); // (4)
      b.handler(new ChannelInitializer<SocketChannel>() {
        private String file_path;

        {
          file_path = path;
        }

        @Override
        public void initChannel(SocketChannel ch)
            throws Exception {
          ch.pipeline().addLast(new HttpClientCodec())
                       .addLast(new ChunkedWriteHandler())
                       .addLast(new MyHttpFileClientHandler(file_path));
        }
      });

      ChannelFuture f = b.connect("localhost", 8088).sync();
      f.channel().closeFuture().sync();
    } catch (Exception e) {
      System.out.println("Caught exception");
      e.printStackTrace();
    } finally {
      group.shutdownGracefully();
    }
  }
}

class MyHttpFileClientHandler extends SimpleChannelInboundHandler<Object> {
  private FileOutputStream fop;
  private String target_path;

  MyHttpFileClientHandler(String path) {
    target_path = path;
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx)
      throws Exception {
    FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "i");
    HttpHeaders.setKeepAlive(request, false);
    ctx.writeAndFlush(request);
  }

  @Override
  public void channelRead0(ChannelHandlerContext ctx, Object in)
      throws Exception {
    if (in instanceof DefaultHttpResponse) {
      DefaultHttpResponse response = (DefaultHttpResponse) in;
      if (!response.getDecoderResult().isSuccess()) {
        System.out.println("Bad response");
        return;
      }
      if (!response.getStatus().equals(HttpResponseStatus.OK)) {
        System.out.println("Not OK");
        return;
      }

      File file = new File(target_path);
      fop = new FileOutputStream(file);
    }

    if (in instanceof DefaultHttpContent) {
      DefaultHttpContent response = (DefaultHttpContent) in;
      ByteBuf content = response.content();

      if (!content.isReadable()) {
        System.out.println("Not readable");
        return;
      }

      int len=-2;

      try {
          len = content.readableBytes();
          content.readBytes(fop, content.readableBytes());
          fop.flush();
      } catch (Exception e) {
        e.printStackTrace(System.out);
        System.out.println("len is " + Integer.toString(len));
      }
    }

    if (in instanceof LastHttpContent) {
      fop.flush();
      fop.close();
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    ctx.close();
  }
}
