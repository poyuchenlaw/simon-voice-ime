package com.simon.voiceime;

/** Pure bitmap math; exclusions are actual ink rectangles, never metadata about diamonds. */
public final class App690FPixels {
    private App690FPixels(){}
    private static double linear(double c){return c<=.04045?c/12.92:Math.pow((c+.055)/1.055,2.4);}
    public static double contrast(int foreground,int background,int surface){
        double a=(foreground>>>24)/255d,b=(background>>>24)/255d,fg=0,bg=0;int[] shifts={16,8,0};double[] weights={.2126,.7152,.0722};
        for(int i=0;i<3;i++){int shift=shifts[i];double back=((background>>>shift)&255)*b+((surface>>>shift)&255)*(1-b),front=((foreground>>>shift)&255)*a+back*(1-a);bg+=weights[i]*linear(back/255);fg+=weights[i]*linear(front/255);}
        return (Math.max(fg,bg)+.05)/(Math.min(fg,bg)+.05);
    }
    public static int delta(int a,int b){int n=0;for(int shift:new int[]{0,8,16})n=Math.max(n,Math.abs(((a>>>shift)&255)-((b>>>shift)&255)));return n;}
    public static int[] diamond(int[] pixels,int w,int h,float density,float[][] ink){
        if(pixels.length!=w*h||density<=0)throw new IllegalArgumentException("bitmap geometry");
        float[][] p={{w/2f,8*density},{w-8*density,h/2f},{w/2f,h-8*density},{8*density,h/2f}};int maximum=0,count=0,offset=Math.max(1,Math.round(2*density));
        for(int edge=0;edge<4;edge++){float[] a=p[edge],b=p[(edge+1)%4];int steps=Math.max(1,(int)Math.ceil(Math.hypot(a[0]-b[0],a[1]-b[1])));
            for(int i=0;i<=steps;i++){int x=Math.round(a[0]+(b[0]-a[0])*i/steps),y=Math.round(a[1]+(b[1]-a[1])*i/steps);
                for(int dy=-1;dy<=1;dy++){int yy=y+dy;if(x<0||x>=w||yy<0||yy+offset>=h)continue;boolean skip=false;
                    for(float[] box:ink)if(x>=box[0]-2&&x<=box[2]+2&&yy<=box[3]+2&&yy+offset>=box[1]-2)skip=true;
                    if(skip)continue;maximum=Math.max(maximum,delta(pixels[yy*w+x],pixels[(yy+offset)*w+x]));count++;}
            }
        }return new int[]{maximum,count};
    }
    public static int[] capsule(float left,float top,float bottom,float visibleTop,float visibleBottom,int screenWidth,float density){
        int w=Math.round(32*density),h=Math.round(24*density),x=Math.max(0,Math.min(Math.round(left-w/2f),screenWidth-w));
        int above=(int)Math.ceil(top-h/2f-8*density),y=above>=visibleTop?above:(int)Math.floor(bottom+8*density-h/2f);
        y=Math.max((int)visibleTop,Math.min(y,(int)visibleBottom-h));return new int[]{x,y,w,h};
    }
}
