package android.graphics;
public class Rect {
 public int left,top,right,bottom; public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;} public Rect(Rect o){this(o.left,o.top,o.right,o.bottom);}
 public int width(){return right-left;} public int height(){return bottom-top;} public boolean isEmpty(){return width()<=0||height()<=0;}
 public boolean intersect(int l,int t,int r,int b){int nl=Math.max(left,l),nt=Math.max(top,t),nr=Math.min(right,r),nb=Math.min(bottom,b);if(nr<=nl||nb<=nt)return false;left=nl;top=nt;right=nr;bottom=nb;return true;}
}