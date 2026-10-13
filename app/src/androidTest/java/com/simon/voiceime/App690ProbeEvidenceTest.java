package com.simon.voiceime;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import org.json.*;

/** Independent controls: a known uniform bitmap, an explicitly drawn diamond,
 * and deletion of the drawing command. Expected values come from those inputs. */
public class App690ProbeEvidenceTest extends App690FSupport {
    public void testScreenMatrixMatchesAttachedView()throws Exception {
        View keyboard=page("dual_hand");main(()->{float[] at={0,0};App690ProbeEvidence.screenMatrix(keyboard).mapPoints(at);int[] expected=new int[2];keyboard.getLocationOnScreen(expected);assertEquals("independent screen x",expected[0],at[0],.5f);assertEquals("independent screen y",expected[1],at[1],.5f);return null;});
    }
    public void testDrawingControls()throws Exception {
        View view=new View(InstrumentationRegistry.getInstrumentation().getTargetContext());view.layout(0,0,120,100);
        Drawable line=new Drawable(){final Paint p=new Paint();public void draw(Canvas c){p.setColor(0xB0FFFFFF);p.setStrokeWidth(1);c.drawLine(5,1,115,1,p);}public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return -3;}};
        view.setBackground(line);line.setBounds(0,0,120,100);
        JSONObject present=App690ProbeEvidence.drawing(view);assertEquals(1.,present.getDouble("stroke_width_px"));assertEquals(0xB0FFFFFF,present.getInt("drawable_highlight_color"));assertEquals(4,present.getJSONArray("stroke_path").length());
        view.setBackground(null);JSONObject deleted=App690ProbeEvidence.drawing(view);assertTrue(deleted.isNull("stroke_width_px"));assertTrue(deleted.isNull("stroke_path"));
        int w=180,h=200;Bitmap image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);image.eraseColor(0xFF202020);int[] pixels=new int[w*h];image.getPixels(pixels,0,w,0,0,w,h);
        int[] clean=App690FPixels.diamond(pixels,w,h,1,new float[0][]);assertEquals(0,clean[0]);assertTrue(clean[1]>0);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(0xFFFFFFFF);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);Path diamond=new Path();diamond.moveTo(w/2f,8);diamond.lineTo(w-8,h/2f);diamond.lineTo(w/2f,h-8);diamond.lineTo(8,h/2f);diamond.close();new Canvas(image).drawPath(diamond,p);image.getPixels(pixels,0,w,0,0,w,h);
        assertTrue("explicit diamond must exceed retained 2-channel threshold",App690FPixels.diamond(pixels,w,h,1,new float[0][])[0]>2);image.recycle();
    }
}
