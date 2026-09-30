package com.simon.voiceime;

import java.io.*;
import java.util.*;

/** Offline-ready, Android-free per-layout Gaussian model. No product wiring.
 * Coordinates are absolute layout pixels; updates require a confirmed intended
 * key (never the model's own prediction). Geometry is immutable for a model.
 */
public final class TouchModel {
    public static final class Key {
        public final String screen, key;
        public final double x, y, pitchX, pitchY;
        public Key(String screen, String key, double x, double y, double pitchX, double pitchY) {
            if (screen == null || screen.isEmpty() || key == null || key.isEmpty()
                    || !finite(x,y,pitchX,pitchY) || pitchX<=0 || pitchY<=0)
                throw new IllegalArgumentException("invalid key geometry");
            this.screen=screen; this.key=key; this.x=x; this.y=y;
            this.pitchX=pitchX; this.pitchY=pitchY;
        }
    }
    public static final class Alternative {
        public final String key;
        public final double probability, logLikelihood;
        Alternative(String key, double probability, double logLikelihood) {
            this.key=key; this.probability=probability; this.logLikelihood=logLikelihood;
        }
    }
    public static final class Parameters {
        public final long count;
        public final double meanX, meanY, varianceX, covarianceXY, varianceY;
        Parameters(State s, double shrinkage) {
            count=s.n;
            double w=s.n/(s.n+shrinkage);
            meanX=s.mx*w; meanY=s.my*w;
            varianceX=s.xx; covarianceXY=s.xy; varianceY=s.yy;
        }
    }
    private static final class State {
        final Key key;
        long n;
        double mx,my,xx,xy,yy;
        State(Key k) { key=k; reset(); }
        void reset() { n=0; mx=my=xy=0; xx=square(key.pitchX*.28); yy=square(key.pitchY*.28); }
    }
    private final LinkedHashMap<String,State> states=new LinkedHashMap<>();
    private final double step, shrinkage;
    private final boolean frequencyPrior;
    public TouchModel(List<Key> keys) { this(keys,.03,20,false); }
    public TouchModel(List<Key> keys, double step, double shrinkage, boolean frequencyPrior) {
        if(keys==null || keys.isEmpty() || !finite(step,shrinkage) || step<=0 || step>1 || shrinkage<=0)
            throw new IllegalArgumentException("invalid model configuration");
        this.step=step; this.shrinkage=shrinkage; this.frequencyPrior=frequencyPrior;
        for(Key k:keys) if(states.put(id(k.screen,k.key),new State(k))!=null)
            throw new IllegalArgumentException("duplicate key");
    }
    private static String id(String screen,String key) { return screen.length()+":"+screen+key; }
    private static double square(double x) { return x*x; }
    private static boolean finite(double... xs) { for(double x:xs) if(!Double.isFinite(x)) return false; return true; }
    private State state(String screen,String key) {
        State s=states.get(id(screen,key));
        if(s==null) throw new IllegalArgumentException("unknown layout/key: "+screen+"/"+key);
        return s;
    }
    public synchronized Parameters parameters(String screen,String key) { return new Parameters(state(screen,key),shrinkage); }
    public synchronized List<Alternative> predict(String screen,double x,double y) {
        if(!finite(x,y)) throw new IllegalArgumentException("nonfinite touch");
        List<State> layout=new ArrayList<>();
        long total=0;
        for(State s:states.values()) if(s.key.screen.equals(screen)) { layout.add(s); total+=s.n; }
        if(layout.isEmpty()) throw new IllegalArgumentException("unknown layout");
        double[] logs=new double[layout.size()], likelihoods=new double[layout.size()];
        double max=-Double.MAX_VALUE;
        for(int i=0;i<layout.size();i++) {
            State s=layout.get(i); Parameters p=new Parameters(s,shrinkage);
            // Saturate remote touches before arithmetic: finite inputs must not yield NaNs.
            double dx=Math.max(-1e6,Math.min(1e6,x-s.key.x-p.meanX));
            double dy=Math.max(-1e6,Math.min(1e6,y-s.key.y-p.meanY));
            double det=s.xx*s.yy-s.xy*s.xy;
            double q=(s.yy*dx*dx-2*s.xy*dx*dy+s.xx*dy*dy)/det;
            likelihoods[i]=-.5*(q+Math.log(det)+2*Math.log(2*Math.PI));
            double prior=frequencyPrior ? (s.n+20.0)/(total+20.0*layout.size()) : 1.0/layout.size();
            logs[i]=likelihoods[i]+Math.log(prior); max=Math.max(max,logs[i]);
        }
        double sum=0; for(double l:logs) sum+=Math.exp(l-max);
        List<Alternative> result=new ArrayList<>();
        for(int i=0;i<logs.length;i++) result.add(new Alternative(layout.get(i).key.key,Math.exp(logs[i]-max)/sum,likelihoods[i]));
        result.sort(Comparator.comparingDouble((Alternative a)->a.probability).reversed().thenComparing(a->a.key));
        return Collections.unmodifiableList(result);
    }
    public synchronized void updateConfirmed(String screen,String intendedKey,double x,double y) {
        if(!finite(x,y)) throw new IllegalArgumentException("nonfinite touch");
        State s=state(screen,intendedKey);
        double dx=Math.max(-2*s.key.pitchX,Math.min(2*s.key.pitchX,x-s.key.x));
        double dy=Math.max(-2*s.key.pitchY,Math.min(2*s.key.pitchY,y-s.key.y));
        double ex=dx-s.mx, ey=dy-s.my;
        s.mx+=step*ex; s.my+=step*ey;
        double cap=Math.min(s.key.pitchX,s.key.pitchY)/3, norm=Math.hypot(s.mx,s.my);
        if(norm>cap) { s.mx*=cap/norm; s.my*=cap/norm; }
        s.xx=(1-step)*(s.xx+step*ex*ex);
        s.xy=(1-step)*(s.xy+step*ex*ey);
        s.yy=(1-step)*(s.yy+step*ey*ey);
        double eigenMin=(s.xx+s.yy-Math.hypot(s.xx-s.yy,2*s.xy))/2;
        double add=Math.max(0,square(.18*Math.min(s.key.pitchX,s.key.pitchY))-eigenMin);
        s.xx+=add; s.yy+=add; s.n++;
    }
    public synchronized void reset() { for(State s:states.values()) s.reset(); }
    /** Versioned portable binary parameter snapshot, usable for persistence/rollback. */
    public synchronized byte[] snapshot() {
        try {
            ByteArrayOutputStream b=new ByteArrayOutputStream(); DataOutputStream d=new DataOutputStream(b);
            d.writeUTF("TouchModel/1"); d.writeDouble(step); d.writeDouble(shrinkage); d.writeBoolean(frequencyPrior);
            d.writeInt(states.size());
            for(State s:states.values()) {
                Key k=s.key; d.writeUTF(k.screen); d.writeUTF(k.key);
                for(double v:new double[]{k.x,k.y,k.pitchX,k.pitchY}) d.writeDouble(v);
                d.writeLong(s.n); for(double v:new double[]{s.mx,s.my,s.xx,s.xy,s.yy}) d.writeDouble(v);
            }
            d.flush(); return b.toByteArray();
        } catch(IOException e) { throw new IllegalStateException(e); }
    }
    /** Validate entire snapshot before applying it; rejected snapshots leave state unchanged. */
    public synchronized void rollback(byte[] snapshot) throws IOException {
        DataInputStream d=new DataInputStream(new ByteArrayInputStream(snapshot));
        if(!d.readUTF().equals("TouchModel/1") || d.readDouble()!=step || d.readDouble()!=shrinkage
                || d.readBoolean()!=frequencyPrior || d.readInt()!=states.size()) throw new IOException("snapshot configuration mismatch");
        List<State> parsed=new ArrayList<>();
        for(State existing:states.values()) {
            Key k=existing.key;
            if(!d.readUTF().equals(k.screen) || !d.readUTF().equals(k.key)) throw new IOException("snapshot layout mismatch");
            for(double value:new double[]{k.x,k.y,k.pitchX,k.pitchY}) if(d.readDouble()!=value) throw new IOException("snapshot geometry mismatch");
            State s=new State(k); s.n=d.readLong(); s.mx=d.readDouble(); s.my=d.readDouble();
            s.xx=d.readDouble(); s.xy=d.readDouble(); s.yy=d.readDouble();
            double eigenMin=(s.xx+s.yy-Math.hypot(s.xx-s.yy,2*s.xy))/2;
            double floor=square(.18*Math.min(k.pitchX,k.pitchY));
            if(s.n<0 || !finite(s.mx,s.my,s.xx,s.xy,s.yy) || Math.hypot(s.mx,s.my)>Math.min(k.pitchX,k.pitchY)/3+1e-8
                    || eigenMin<floor-1e-8 || s.xx*s.yy-s.xy*s.xy<=0) throw new IOException("unsafe snapshot parameters");
            parsed.add(s);
        }
        if(d.read()!=-1) throw new IOException("trailing snapshot bytes");
        for(State s:parsed) states.put(id(s.key.screen,s.key.key),s);
    }
}
