import android.graphics.Bitmap; import android.graphics.Rect; import cn.xianhua.pocketkit.core.automation.Match; import cn.xianhua.pocketkit.core.automation.TemplateMatcher;
public final class TemplateMatcherSelfTest {
 private static int checks;
 private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 private static Bitmap screen(int w,int h){Bitmap b=new Bitmap(w,h);for(int y=0;y<h;y++)for(int x=0;x<w;x++){int v=(x*3+y*5)&255;b.setPixel(x,y,0xff000000|(v<<16)|(v<<8)|v);}return b;}
 private static Bitmap template(){Bitmap b=new Bitmap(19,13);for(int y=0;y<13;y++)for(int x=0;x<19;x++){int v=(x*17+y*29+x*y*3)&255;b.setPixel(x,y,0xff000000|(v<<16)|(v<<8)|v);}return b;}
 private static void put(Bitmap s,Bitmap t,int ox,int oy,int add){for(int y=0;y<t.getHeight();y++)for(int x=0;x<t.getWidth();x++){int v=Math.min(255,(t.getPixel(x,y)&255)+add);s.setPixel(ox+x,oy+y,0xff000000|(v<<16)|(v<<8)|v);}}
 public static void main(String[] args){
  Bitmap s=screen(240,160),t=template();put(s,t,137,91,18);
  Match found=TemplateMatcher.find(s,t,new Rect(100,60,210,140),.92,3000);check(found.found,"brightness match");check(found.left==137&&found.top==91,"ROI absolute coordinates");check(found.score>.99,"NCC score");
  Match miss=TemplateMatcher.find(s,template(),new Rect(0,0,50,50),.95,3000);check(!miss.found,"not found");
  Match edge=TemplateMatcher.find(s,t,new Rect(137,91,156,104),.9,3000);check(edge.found&&edge.left==137,"ROI edge");
  try{TemplateMatcher.find(s,t,null,1.1,100);throw new AssertionError("threshold accepted");}catch(IllegalArgumentException expected){checks++;}
  Bitmap full=screen(1080,2400),large=new Bitmap(64,48);for(int y=0;y<48;y++)for(int x=0;x<64;x++){int v=(x*31+y*17+x*y)&255;large.setPixel(x,y,0xff000000|(v<<16)|(v<<8)|v);full.setPixel(701+x,1103+y,0xff000000|(v<<16)|(v<<8)|v);}long started=System.nanoTime();Match perf=TemplateMatcher.find(full,large,null,.98,30000);long elapsed=(System.nanoTime()-started)/1000000;check(perf.found&&perf.left==701&&perf.top==1103,"full-screen定位");System.out.println("TemplateMatcherSelfTest PASS checks="+checks+" fullScreen1080x2400_ms="+elapsed+" score="+perf.score+"; no device/MLKit claim");
 }
}
