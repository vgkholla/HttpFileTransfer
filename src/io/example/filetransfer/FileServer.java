package io.example.filetransfer;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.channel.ChannelProgressiveFutureListener;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.CharsetUtil;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import javax.activation.MimetypesFileTypeMap;


public class FileServer {
  private int port;
  private static final String sourceDir = "/tmp/server/";

  public static String getSourceDir() {
    return sourceDir;
  }

  public static void main(String[] args)
      throws Exception {
    new FileServer(8088).run();
  }

  public FileServer(int port) {
    this.port = port;
    File f = new File(sourceDir);
    if (!f.exists()) {
      f.mkdir();
    }
  }

  public void run()
      throws Exception {
    EventLoopGroup bossGroup = new NioEventLoopGroup();
    EventLoopGroup workerGroup = new NioEventLoopGroup();

    try {
       ServerBootstrap b = new ServerBootstrap();
      b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).option(ChannelOption.SO_BACKLOG, 100)
          .handler(new LoggingHandler(LogLevel.INFO)).childHandler(new ChannelInitializer<SocketChannel>() {
        @Override
        public void initChannel(SocketChannel ch)
            throws Exception {
          ch.pipeline().addLast(new HttpServerCodec())
                       .addLast(new ChunkedWriteHandler())
                       .addLast(new MyFileServerHandler());
        }
      });
      ChannelFuture f = b.bind(port).sync();
      System.out.println("Open your web browser and navigate to " +
          "http://127.0.0.1:" + port + '/');
      f.channel().closeFuture().sync();
    } finally {
      workerGroup.shutdownGracefully();
      bossGroup.shutdownGracefully();
    }
  }
}

class MyFileServerHandler extends SimpleChannelInboundHandler<HttpObject> {

  @Override
  public void channelRead0(ChannelHandlerContext ctx, HttpObject msg)
      throws Exception {
    if (msg instanceof HttpRequest) {
      HttpRequest request = (HttpRequest) msg;
      if (!request.getDecoderResult().isSuccess()) {
        sendError(ctx, HttpResponseStatus.BAD_REQUEST);
        return;
      }

      if (request.getMethod() != HttpMethod.GET) {
        sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
        return;
      }

      final String uri = request.getUri();
      final String path = FileServer.getSourceDir() + uri;
      System.out.println(path);
      if (path == null) {
        sendError(ctx, HttpResponseStatus.FORBIDDEN);
        return;
      }

      File file = new File(path);
      if (file.isHidden() || !file.exists()) {
        sendError(ctx, HttpResponseStatus.NOT_FOUND);
        return;
      }

      if (!file.isFile()) {
        sendError(ctx, HttpResponseStatus.FORBIDDEN);
        return;
      }

      RandomAccessFile raf;
      try {
        raf = new RandomAccessFile(file, "r");
      } catch (FileNotFoundException ignore) {
        sendError(ctx, HttpResponseStatus.NOT_FOUND);
        return;
      }
      long fileLength = raf.length();

      HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
      HttpHeaders.setContentLength(response, fileLength);
      setContentTypeHeader(response, file);
      response.headers().set(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
      if (HttpHeaders.isKeepAlive(request)) {
        response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
      }

      // Write the initial line and the header.
      ctx.write(response);

      // Write the content.
      ChannelFuture sendFileFuture;
      ChannelFuture lastContentFuture;
      sendFileFuture = ctx.writeAndFlush(new HttpChunkedInput(new ChunkedFile(raf, 0, fileLength, 8192)),
          ctx.newProgressivePromise());
      // HttpChunkedInput will write the end marker (LastHttpContent) for us.
      lastContentFuture = sendFileFuture;

      sendFileFuture.addListener(new ChannelProgressiveFutureListener() {
        @Override
        public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {
          if (total < 0) { // total unknown
            System.out.println(future.channel() + " Transfer progress: " + progress);
          } else {
            System.out.println(future.channel() + " Transfer progress: " + progress + " / " + total);
          }
        }

        @Override
        public void operationComplete(ChannelProgressiveFuture future) {
          System.out.println(future.channel() + " Transfer complete.");
        }
      });

      // Decide whether to close the connection or not.
      if (!HttpHeaders.isKeepAlive(request)) {
        // Close the connection when the whole content is written out.
        lastContentFuture.addListener(ChannelFutureListener.CLOSE);
      }
    }
  }

  private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
        Unpooled.copiedBuffer("Failure: " + status + "\r\n", CharsetUtil.UTF_8));
    response.headers().set(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream");

    // Close the connection as soon as the error message is sent.
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  private static void setContentTypeHeader(HttpResponse response, File file) {
    response.headers()
        .set(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream");
  }
}
