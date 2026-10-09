package com.simon.voiceime;

/** Dispatch timing only; arbitrary Handler/Runnable descriptions never leave this parser. */
final class MainDispatchMonitor {
    interface Sink { void record(int ms,String target,String callback,int what,boolean input,boolean candidates); }
    private final Sink sink;
    private final long[] emitted = new long[60];
    private int next, count;
    private long started;
    private String dispatch;
    private boolean inputActive, candidatesActive;
    private static final String CLASS = "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*";
    private static final java.util.regex.Pattern HEADER = java.util.regex.Pattern.compile(
            "^>>>>> Dispatching to Handler \\(("+CLASS+")\\) \\{[0-9a-fA-F]+\\} (.*): (-?[0-9]+)$");
    private static final java.util.regex.Pattern CALLBACK = java.util.regex.Pattern.compile("^("+CLASS+")(?:@[0-9a-fA-F]+)?$");
    MainDispatchMonitor(Sink sink) { this.sink=sink; }
    void input() { if(dispatch!=null)inputActive=true; }
    void candidates() { if(dispatch!=null)candidatesActive=true; }
    void accept(String line,long now,boolean input) {
        if(line.startsWith(">>>>>")) { started=now;dispatch=line;inputActive=input;candidatesActive=false;return; }
        if(!line.startsWith("<<<<<")||dispatch==null)return;
        String header=dispatch;dispatch=null;
        long ms=now-started;
        if(ms<=100||ms>Integer.MAX_VALUE||count==60&&now-emitted[next]<60000)return;
        java.util.regex.Matcher match=HEADER.matcher(header);
        if(!match.matches())return;
        String target=match.group(1),raw=match.group(2),callback="";
        if(target.length()>256)return;
        java.util.regex.Matcher cb=CALLBACK.matcher(raw);
        if(!"null".equals(raw)&&cb.matches()&&cb.group(1).length()<=256)callback=cb.group(1);
        int what;
        try { what=Integer.parseInt(match.group(3)); } catch(NumberFormatException invalid) { return; }
        emitted[next]=now;next=(next+1)%60;if(count<60)count++;
        sink.record((int)ms,target,callback,what,inputActive||input,candidatesActive);
    }
}
