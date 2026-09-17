package com.manliao.backend.media;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.w3c.dom.Node;
/** Reads only the first frame of an already normalized, bounded image. */
final class ImageDerivatives {
 record Images(byte[] thumbnail,byte[] display){}
 static Images create(byte[] normalized,boolean gif)throws IOException{
  try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(normalized))){
   var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Unreadable normalized image");
   var reader=readers.next();
   try{
    reader.setInput(input,false,false);int width=reader.getWidth(0),height=reader.getHeight(0),x=0,y=0;
    Node global=null,control=null;
    if(gif){
     var stream=reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");var screen=child(stream,"LogicalScreenDescriptor");
     width=number(screen,"logicalScreenWidth");height=number(screen,"logicalScreenHeight");global=child(stream,"GlobalColorTable");
     var meta=reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0");var frame=child(meta,"ImageDescriptor");
     x=number(frame,"imageLeftPosition");y=number(frame,"imageTopPosition");control=child(meta,"GraphicControlExtension");
    }
    if(width<1||height<1||(long)width*height>4000000||x<0||y<0||(long)x+reader.getWidth(0)>width||(long)y+reader.getHeight(0)>height)
     throw new IOException("Invalid image canvas");
    var decoded=reader.read(0);BufferedImage canvas=decoded;
    try{
     if(gif){
      canvas=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);var graphics=canvas.createGraphics();
      try{
       boolean transparent=control!=null&&"TRUE".equals(control.getAttributes().getNamedItem("transparentColorFlag").getNodeValue());
       if(!transparent&&global!=null){
        int background=number(global,"backgroundColorIndex");
        for(Node c=global.getFirstChild();c!=null;c=c.getNextSibling())if(number(c,"index")==background){
         graphics.setColor(new Color(number(c,"red"),number(c,"green"),number(c,"blue")));graphics.fillRect(0,0,width,height);break;
        }
       }
       graphics.drawImage(decoded,x,y,null);
      }finally{graphics.dispose();}
     }
     return new Images(resize(canvas,320),gif?null:resize(canvas,1280));
    }finally{if(canvas!=decoded)canvas.flush();decoded.flush();}
   }finally{reader.dispose();}
  }catch(RuntimeException invalid){throw new IOException("Invalid normalized image",invalid);}
 }
 private static byte[] resize(BufferedImage input,int limit)throws IOException{
  double ratio=Math.min(1.0,(double)limit/Math.max(input.getWidth(),input.getHeight()));
  int width=Math.max(1,(int)Math.round(input.getWidth()*ratio)),height=Math.max(1,(int)Math.round(input.getHeight()*ratio));
  var output=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);var graphics=output.createGraphics();
  try{
   graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
   graphics.setComposite(AlphaComposite.Src);graphics.drawImage(input,0,0,width,height,null);
  }finally{graphics.dispose();}
  try{var bytes=new ByteArrayOutputStream();if(!ImageIO.write(output,"png",bytes))throw new IOException("PNG encoder unavailable");return bytes.toByteArray();}
  finally{output.flush();}
 }
 private static Node child(Node node,String name){for(Node c=node.getFirstChild();c!=null;c=c.getNextSibling())if(c.getNodeName().equals(name))return c;return null;}
 private static int number(Node node,String name){return Integer.parseInt(node.getAttributes().getNamedItem(name).getNodeValue());}
}
