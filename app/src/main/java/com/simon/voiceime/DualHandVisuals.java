package com.simon.voiceime;

/** Shared geometry and the single functional palette for all keyboard pages. */
public final class DualHandVisuals {
    public static final float LEFT_FRACTION=.40f, WING_FRACTION=.30f, PREVIEW_FRACTION=.40f;
    public static final boolean ENABLE_LANDSCAPE_SPLIT=true;
    private DualHandVisuals(){}
    public static int color(boolean dark,String role){
        switch(role){
            case "left":return dark?0xFF161B26:0xFFFFFFFF;
            case "right":return dark?0xFF1C2230:0xFFFFFFFF;
            case "zero":return dark?0xFF1E293B:0xFFEEF2F6;
            case "core":return dark?0xFF252D3D:0xFFE0E2EC;
            case "tools":return dark?0xFF1A202C:0xFFF8F9FA;
            case "text":return dark?0xFFFFFFFF:0xFF1D2939;
            case "secondary":return dark?0xFF94A3B8:0xFF586174;
            case "hint":return dark?0xFF38BDF8:0xFF0369A1;
            case "border":return dark?0xFF6366F1:0xFF94A3B8;
            case "highlight":return dark?0xFF48546A:0xFFE0F2FE;
            case "accent":return dark?0xFF4ECCA3:0xFF077256;
            case "recording":return dark?0xFFE94560:0xFFE53935;
            case "mode_append":return dark?0xFF4ECCA3:0xFF08745B;
            case "mode_replace":return dark?0xFFF87171:0xFFB42318;
            case "mode_spell":return dark?0xFFF0A500:0xFF8A5300;
            case "mode_translate":return dark?0xFF6BC5F0:0xFF0369A1;
            case "top_highlight":return dark?0x40FFFFFF:0xB0FFFFFF;
            case "bubble":return 0xDA203549;
            case "bubble_text":return 0xFFFFFFFF;
            case "overlay":return 0xE61C2230;
            case "halo":return 0x9938BDF8;
            case "transparent":return 0x00000000;
            case "action_positive_bg":return dark?0xFF2D6A4F:0xFFD7EFE6;
            case "action_negative_bg":return dark?0xFF4A1060:0xFFFCE2E2;
            case "destructive":return dark?0xFFF87171:0xFFB42318;
            default:return dark?0xFF0D1117:0xFFF2F4F7;
        }
    }
    public static float leftFraction(float screenDp){return Math.max(LEFT_FRACTION,136/(screenDp-6));}
    public static float landscapeRowDp(float heightDp){return 182<=heightDp*.52f?50:182<=heightDp*.55f?50:-1;}
    public static boolean guideDot(String central){return central.isEmpty();}
    public static boolean highlight(String direction,String output){return !"tap".equals(direction)&&!output.isEmpty();}
    public static int clamp(int position,int extent,int available){return Math.max(0,Math.min(position,Math.max(0,available-extent)));}
    public static int[] capsulePosition(int x,int top,int bottom,int width,int height){return new int[]{clamp(x-16,32,width),clamp(top>=28?top-20:bottom-4,24,height)};}
}
