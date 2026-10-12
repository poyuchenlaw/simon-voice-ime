package com.simon.voiceime;
import java.util.Arrays;
public final class App690PixelSelfTest {
    public static void main(String[] args){
        int w=100,h=100;int[] p=new int[w*h];Arrays.fill(p,0xff161b26);
        int[] clean=App690FPixels.diamond(p,w,h,1,new float[0][]);assert clean[0]==0&&clean[1]>0;
        p[8*w+50]=0xff202531;assert App690FPixels.diamond(p,w,h,1,new float[0][])[0]>2:"injected diamond must turn red";
        p[8*w+50]=0xff161b26;p[8*w+50]=0xffffffff;assert App690FPixels.diamond(p,w,h,1,new float[][]{{47,5,53,13}})[0]==0:"label ink excluded";
        int[] at=App690FPixels.capsule(100,119,139,100,600,400,1);assert at[0]==84&&at[1]==135;
        at=App690FPixels.capsule(100,120,140,100,600,400,1);assert at[1]==100;
        at=App690FPixels.capsule(5,121,141,100,600,400,1);assert at[0]==0&&at[1]==101;
        at=App690FPixels.capsule(400,200,220,100,600,400,1);assert at[0]==368&&at[1]==180;
        System.out.println("pixel/capsule independent fixtures PASS (diamond mutation >2; 19/20/21dp; left/right clamp)");
    }
}
