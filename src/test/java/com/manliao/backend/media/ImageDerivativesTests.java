package com.manliao.backend.media;
import java.awt.image.*;
import java.io.*;
import java.nio.file.*;
import javax.imageio.*;
import javax.imageio.metadata.*;
import javax.imageio.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
class ImageDerivativesTests {
 Path root;MediaStorage storage;
 @BeforeEach void setup()throws Exception{root=Files.createTempDirectory(Path.of("target"),"derivative-unit-").toAbsolutePath();storage=new MediaStorage(root.toString());}
 byte[] png()throws Exception{var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(10,8,BufferedImage.TYPE_INT_ARGB),"png",out);return out.toByteArray();}
 @Test void failedDerivativeWriteRemovesOnlyNewBundleFiles()throws Exception{
  String id="media_11111111111111111111111111111111";Path blocked=root.resolve(id+".display.png");Files.createDirectory(blocked);Files.writeString(blocked.resolve("keep"),"fixture");
  assertThatThrownBy(()->storage.save(id,new MockMultipartFile("file","x.png","image/png",png()))).isInstanceOf(IOException.class);
  assertThat(Files.exists(root.resolve(id+".png"))).isFalse();assertThat(Files.exists(root.resolve(id+".thumb.png"))).isFalse();
  assertThat(Files.readString(blocked.resolve("keep"))).isEqualTo("fixture");
  try(var files=Files.list(root.resolve("multipart"))){assertThat(files.count()).isZero();}
 }
 @Test void partialDeletionCanRetryAfterOriginalAlreadyGone()throws Exception{
  String id="media_22222222222222222222222222222222";var asset=storage.save(id,new MockMultipartFile("file","x.png","image/png",png()));
  Path blocked=root.resolve(id+".display.png");Files.delete(blocked);Files.createDirectory(blocked);Files.writeString(blocked.resolve("keep"),"fixture");
  assertThatThrownBy(()->storage.delete(asset.key())).isInstanceOf(IOException.class);
  assertThat(Files.exists(root.resolve(asset.key()))).isFalse();assertThat(Files.exists(root.resolve(id+".thumb.png"))).isFalse();
  Files.delete(blocked.resolve("keep"));Files.delete(blocked);storage.delete(asset.key());
  assertThat(Files.exists(blocked)).isFalse();
 }
 @Test void gifThumbnailUsesCanvasOffsetAndPreservesBackgroundOrTransparency()throws Exception{
  for(boolean transparent:new boolean[]{false,true}){
   var thumbnail=ImageIO.read(new ByteArrayInputStream(ImageDerivatives.create(offsetGif(transparent),true).thumbnail()));
   assertThat(thumbnail.getWidth()).isEqualTo(4);assertThat(thumbnail.getHeight()).isEqualTo(4);
   assertThat(thumbnail.getRGB(1,1)).isEqualTo(0xff0000ff);
   if(transparent)assertThat(thumbnail.getRGB(0,0)>>>24).isZero();else assertThat(thumbnail.getRGB(0,0)).isEqualTo(0xffff0000);
  }
 }
 byte[] offsetGif(boolean transparent)throws Exception{
  byte[] red={0,(byte)255,0,0},green={0,0,(byte)255,0},blue={0,0,0,(byte)255};
  var model=transparent?new IndexColorModel(2,4,red,green,blue,0):new IndexColorModel(2,4,red,green,blue);
  var image=new BufferedImage(2,2,BufferedImage.TYPE_BYTE_BINARY,model);
  for(int y=0;y<2;y++)for(int x=0;x<2;x++)image.getRaster().setSample(x,y,0,3);
  var writer=ImageIO.getImageWritersByFormatName("gif").next();var bytes=new ByteArrayOutputStream();
  try(var output=new MemoryCacheImageOutputStream(bytes)){
   var stream=writer.getDefaultStreamMetadata(null);var tree=(IIOMetadataNode)stream.getAsTree("javax_imageio_gif_stream_1.0");
   var screen=(IIOMetadataNode)tree.getElementsByTagName("LogicalScreenDescriptor").item(0);
   screen.setAttribute("logicalScreenWidth","4");screen.setAttribute("logicalScreenHeight","4");screen.setAttribute("colorResolution","8");screen.setAttribute("pixelAspectRatio","0");
   var palette=new IIOMetadataNode("GlobalColorTable");palette.setAttribute("sizeOfGlobalColorTable","4");palette.setAttribute("backgroundColorIndex","1");palette.setAttribute("sortFlag","FALSE");
   for(int i=0;i<4;i++){var color=new IIOMetadataNode("ColorTableEntry");color.setAttribute("index",""+i);color.setAttribute("red",""+(red[i]&255));color.setAttribute("green",""+(green[i]&255));color.setAttribute("blue",""+(blue[i]&255));palette.appendChild(color);}
   tree.appendChild(palette);stream.setFromTree("javax_imageio_gif_stream_1.0",tree);
   var metadata=writer.getDefaultImageMetadata(new ImageTypeSpecifier(model,image.getSampleModel()),null);
   var meta=(IIOMetadataNode)metadata.getAsTree("javax_imageio_gif_image_1.0");var position=(IIOMetadataNode)meta.getElementsByTagName("ImageDescriptor").item(0);
   position.setAttribute("imageLeftPosition","1");position.setAttribute("imageTopPosition","1");
   metadata.setFromTree("javax_imageio_gif_image_1.0",meta);writer.setOutput(output);writer.prepareWriteSequence(stream);writer.writeToSequence(new IIOImage(image,null,metadata),null);writer.endWriteSequence();output.flush();return bytes.toByteArray();
  }finally{writer.dispose();}
 }
}
