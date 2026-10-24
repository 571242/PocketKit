package cn.xianhua.pocketkit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Small bundled line icons: stable across Android fonts and emoji versions. */
public final class ToolIconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String kind;
    private final int color;
    public ToolIconView(Context context, String kind, int color) {
        super(context); this.kind = kind; this.color = color;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        c.save(); float size = Math.min(getWidth()-getPaddingLeft()-getPaddingRight(), getHeight()-getPaddingTop()-getPaddingBottom());
        c.translate((getWidth()-size)/2f,(getHeight()-size)/2f); c.scale(size/32f,size/32f);
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        switch (kind) {
            case "影音变声": case "wave":
                line(c,5,13,5,19); line(c,10,8,10,24); line(c,16,4,16,28); line(c,22,10,22,22); line(c,27,13,27,19); break;
            case "图片编辑":
                c.drawRoundRect(4,5,28,27,4,4,paint); c.drawCircle(21,12,2,paint);
                path(c,5,23,12,15,18,21,22,17,28,23); break;
            case "文档扫描":
                c.drawRoundRect(7,3,25,29,3,3,paint); line(c,11,11,21,11); line(c,11,16,21,16); line(c,11,21,18,21); break;
            case "文字处理":
                line(c,6,7,26,7); line(c,16,7,16,26); line(c,11,26,21,26); line(c,6,7,6,11); line(c,26,7,26,11); break;
            case "计算换算":
                c.drawRoundRect(5,3,27,29,4,4,paint); line(c,10,10,22,10); line(c,10,17,13,17); line(c,11.5f,15.5f,11.5f,18.5f); line(c,20,17,23,17); line(c,10,24,13,24); line(c,20,24,23,24); break;
            case "时间效率":
                c.drawCircle(16,17,11,paint); line(c,16,10,16,17); line(c,16,17,21,20); line(c,12,2,20,2); break;
            case "生活查询":
                path(c,5,14,16,4,27,14); path(c,8,12,8,28,24,28,24,12); path(c,13,28,13,20,19,20,19,28); break;
            case "文件管理":
                path(c,4,11,4,7,13,7,16,11,28,11,28,27,4,27,4,11); line(c,4,15,28,15); break;
            case "网络工具":
                c.drawCircle(16,16,12,paint); c.drawOval(10,4,22,28,paint); line(c,4,16,28,16); break;
            case "设备测量":
                c.drawRoundRect(8,3,24,29,3,3,paint); line(c,13,7,19,7); line(c,14,25,18,25); path(c,11,17,14,13,17,19,21,14); break;
            case "自动化":
                path(c,18,3,7,18,15,18,13,29,26,13,18,13,18,3); break;
            case "search":
                c.drawCircle(13,13,8,paint); line(c,19,19,27,27); break;
            case "star":
                path(c,16,3,20,11,29,12,22,19,24,28,16,24,8,28,10,19,3,12,12,11,16,3); break;
            default:
                c.drawRoundRect(4,4,13,13,2,2,paint); c.drawRoundRect(19,4,28,13,2,2,paint);
                c.drawRoundRect(4,19,13,28,2,2,paint); c.drawRoundRect(19,19,28,28,2,2,paint);
        }
        c.restore();
    }
    private void line(Canvas c,float a,float b,float d,float e) { c.drawLine(a,b,d,e,paint); }
    private void path(Canvas c,float... points) {
        Path p=new Path(); p.moveTo(points[0],points[1]);
        for(int i=2;i<points.length;i+=2)p.lineTo(points[i],points[i+1]); c.drawPath(p,paint);
    }
}
