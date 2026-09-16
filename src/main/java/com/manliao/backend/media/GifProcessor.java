package com.manliao.backend.media;
import java.io.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.metadata.*;
import javax.imageio.stream.*;
import org.w3c.dom.Node;
import com.manliao.backend.common.ApiError;
/** Re-encode each bounded frame, retaining animation controls but discarding source extensions. */
final class GifProcessor {
 record Decoded(byte[] bytes,int width,int height,int durationMs){}
 static Decoded decode(byte[] bytes)throws IOException{
  if(bytes.length<14||bytes[bytes.length-1]!=0x3b)throw invalid();
  var reader=ImageIO.getImageReadersByFormatName("gif").next();
  var writer=ImageIO.getImageWritersByFormatName("gif").next();
  try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes));var buffer=new ByteArrayOutputStream();var output=new MemoryCacheImageOutputStream(buffer)){
   reader.addIIOReadWarningListener((r,warning)->{throw invalid();});
   reader.setInput(input,false,false);
   var source=reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
   Node screen=child(source,"LogicalScreenDescriptor");
   int width=number(screen,"logicalScreenWidth"),height=number(screen,"logicalScreenHeight");
   if(width<1||height<1||(long)width*height>1000000)throw limit();
   int frames=reader.getNumImages(true);
   if(frames<1||frames>120||(long)width*height*frames>16000000)throw limit();
   var stream=writer.getDefaultStreamMetadata(null);var tree=stream.getAsTree("javax_imageio_gif_stream_1.0");
   Node target=child(tree,"LogicalScreenDescriptor");
   set(target,"logicalScreenWidth",width);set(target,"logicalScreenHeight",height);set(target,"colorResolution",8);set(target,"pixelAspectRatio",0);
   Node palette=child(source,"GlobalColorTable");if(palette!=null)tree.appendChild(palette);
   stream.setFromTree("javax_imageio_gif_stream_1.0",tree);writer.setOutput(output);writer.prepareWriteSequence(stream);
   int centiseconds=0;
   for(int i=0;i<frames;i++){
    var original=reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
    Node descriptor=child(original,"ImageDescriptor"),control=child(original,"GraphicControlExtension");
    int x=number(descriptor,"imageLeftPosition"),y=number(descriptor,"imageTopPosition");
    int w=reader.getWidth(i),h=reader.getHeight(i);
    if(x<0||y<0||w<1||h<1||(long)x+w>width||(long)y+h>height)throw invalid();
    int delay=control==null?10:Math.max(2,number(control,"delayTime"));
    centiseconds+=delay;if(centiseconds>6000)throw limit();
    var image=reader.read(i);
    var metadata=writer.getDefaultImageMetadata(new ImageTypeSpecifier(image.getColorModel(),image.getSampleModel()),null);
    var node=metadata.getAsTree("javax_imageio_gif_image_1.0");
    Node position=child(node,"ImageDescriptor");set(position,"imageLeftPosition",x);set(position,"imageTopPosition",y);
    Node gce=child(node,"GraphicControlExtension");set(gce,"delayTime",delay);
    if(control!=null){
     for(String attribute:List.of("disposalMethod","transparentColorFlag","transparentColorIndex"))
      gce.getAttributes().getNamedItem(attribute).setNodeValue(control.getAttributes().getNamedItem(attribute).getNodeValue());
    }
    byte[] repeats=loop(original);
    if(i==0&&frames>1&&repeats!=null){
     var extensions=new IIOMetadataNode("ApplicationExtensions");var loop=new IIOMetadataNode("ApplicationExtension");
     loop.setAttribute("applicationID","NETSCAPE");loop.setAttribute("authenticationCode","2.0");
     loop.setUserObject(repeats);extensions.appendChild(loop);node.appendChild(extensions);
    }
    metadata.setFromTree("javax_imageio_gif_image_1.0",node);writer.writeToSequence(new IIOImage(image,null,metadata),null);
    image.flush();
   }
   writer.endWriteSequence();output.flush();
   byte[] normalized=buffer.toByteArray();if(normalized.length>16*1024*1024)throw limit();
   return new Decoded(normalized,width,height,centiseconds*10);
  }catch(ApiError e){throw e;}catch(IOException|RuntimeException e){var error=invalid();error.initCause(e);throw error;}
  finally{reader.dispose();writer.dispose();}
 }
 private static byte[] loop(Node original){
  Node extensions=child(original,"ApplicationExtensions");if(extensions==null)return null;
  for(Node n=extensions.getFirstChild();n!=null;n=n.getNextSibling()){
   String app=n.getAttributes().getNamedItem("applicationID").getNodeValue();
   if(!Set.of("NETSCAPE","ANIMEXTS").contains(app))continue;
   Object value=((IIOMetadataNode)n).getUserObject();
   if(value instanceof byte[] data&&data.length==3&&data[0]==1)return data.clone();
  }return null;
 }
 private static Node child(Node node,String name){for(Node c=node.getFirstChild();c!=null;c=c.getNextSibling())if(c.getNodeName().equals(name))return c;return null;}
 private static int number(Node node,String key){return Integer.parseInt(node.getAttributes().getNamedItem(key).getNodeValue());}
 private static void set(Node node,String key,int value){node.getAttributes().getNamedItem(key).setNodeValue(Integer.toString(value));}
 private static ApiError invalid(){return new ApiError(400,"MEDIA_TYPE_INVALID","需要有效、完整的 GIF 图片");}
 private static ApiError limit(){return new ApiError(413,"MEDIA_FILE_TOO_LARGE","GIF 超过画布、帧数、总像素或 60 秒时长限制");}
}
