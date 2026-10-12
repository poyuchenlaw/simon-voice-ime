package com.simon.voiceime;
/** Screen coordinates copied from 6.89.1 showMarker, including pixel rounding. */
final class CorrectionPosition {
    static int[] place(float left,float top,float bottom,int width,int height,int screenWidth,int visibleTop,int visibleBottom,float density){
        int x=Math.max(0,Math.min(Math.round(left-width/2f),Math.max(0,screenWidth-width)));
        int above=(int)Math.ceil(top-height/2f-8*density);
        int y=above>=visibleTop?above:(int)Math.floor(bottom+8*density-height/2f);
        y=Math.max(visibleTop,Math.min(y,visibleBottom-height));return new int[]{x,y};
    }
}
