package cn.xianhua.pocketkit.core.automation;
public class Match { public final boolean found; public final int left,top,right,bottom; public final String text; public final double score; public Match(boolean f,int l,int t,int r,int b,String t0,double s){found=f;left=l;top=t;right=r;bottom=b;text=t0;score=s;} public static Match notFound(){return new Match(false,0,0,0,0,"",0);} }
