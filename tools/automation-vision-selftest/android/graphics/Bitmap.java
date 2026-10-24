package android.graphics;
public class Bitmap {
  private final int width,height; private final int[] pixels; private boolean recycled;
  public Bitmap(int width,int height){this.width=width;this.height=height;pixels=new int[width*height];}
  public int getWidth(){return width;} public int getHeight(){return height;}
  public int getPixel(int x,int y){return pixels[y*width+x];} public void setPixel(int x,int y,int c){pixels[y*width+x]=c;}
  public void getPixels(int[] dst,int offset,int stride,int x,int y,int w,int h){for(int row=0;row<h;row++)System.arraycopy(pixels,(y+row)*width+x,dst,offset+row*stride,w);}
  public boolean isRecycled(){return recycled;} public void recycle(){recycled=true;}
}