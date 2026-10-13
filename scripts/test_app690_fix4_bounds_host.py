from pathlib import Path
import subprocess
w=Path(__file__).resolve().parents[1];s=(w/'app/src/main/java/com/simon/voiceime/CorrectionEditor.java').read_text();start=s.index('final long generation=markerRequestGeneration;');end=s.index('                return;',start);body=s[start:end]
template=r'''import java.util.concurrent.Executor;import java.util.function.Consumer;
class Fix4BoundsHarness {
static class RectF{float left,top,right,bottom;RectF(){}RectF(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}}
static class Matrix{void mapRect(RectF r){r.top+=1804;r.bottom+=1804;}}
static class TextBoundsInfo{int start=0,end=3;float left=10;int getStartIndex(){return start;}int getEndIndex(){return end;}void getCharacterBounds(int i,RectF r){r.left=left;r.top=27;r.right=67;r.bottom=109;}void getMatrix(Matrix m){}}
static class Result{TextBoundsInfo value;Result(TextBoundsInfo v){value=v;}TextBoundsInfo getTextBoundsInfo(){return value;}}
static class Connection{Consumer<Result> answer;Executor executor;void requestTextBoundsInfo(RectF r,Executor e,Consumer<Result> c){answer=c;executor=e;}void finish(TextBoundsInfo i){executor.execute(()->answer.accept(new Result(i)));}}
static class Snapshot{final Connection connection=new Connection();String text="保稅區";boolean same(Snapshot other){return other!=null&&text.equals(other.text);}}
static class View{boolean shown=true;boolean isShown(){return shown;}}
static class Page{boolean blocked;View v=new View();boolean blocked(){return blocked;}View view(){return v;}}
static class Resources{android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();}}
static class Service{boolean protectedField;boolean dualHandProtected(){return protectedField;}Resources getResources(){return new Resources();}}
static class Handler{void post(Runnable r){r.run();}}
long markerRequestGeneration;boolean closed;Page page=new Page();Service service=new Service();Handler ui=new Handler();Snapshot current=new Snapshot();RectF actual;int called;Snapshot snapshot(){return current;}
void showMarkerWithBounds(Snapshot owner,int base,int index,boolean composing,RectF exact,boolean requested){called++;actual=exact;if(!requested)throw new AssertionError("one query only");}
void request(Snapshot owner,int base,int index,boolean composing,String text){/* BODY */}
static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
public static void main(String[] args){int tests=0;
Fix4BoundsHarness h=new Fix4BoundsHarness();Snapshot o=h.current;h.request(o,0,0,false,o.text);o.connection.finish(new TextBoundsInfo());check(h.called==1&&h.actual.left==10&&h.actual.top==1831&&h.actual.bottom==1913,"Layout fixture mapped once");tests++;
h=new Fix4BoundsHarness();o=h.current;h.request(o,0,0,false,o.text);o.connection.finish(null);check(h.called==1&&h.actual==null,"unsupported keeps prior fallback");tests++;
for(int reason=0;reason<6;reason++){h=new Fix4BoundsHarness();o=h.current;h.request(o,0,0,false,o.text);if(reason==0)h.markerRequestGeneration++;if(reason==1){h.current=new Snapshot();h.current.text="已變動";}if(reason==2)h.closed=true;if(reason==3)h.service.protectedField=true;if(reason==4)h.page.blocked=true;if(reason==5)h.page.v.shown=false;o.connection.finish(new TextBoundsInfo());check(h.called==0,"stale/cancel/page/protection reason"+reason);tests++;}
h=new Fix4BoundsHarness();o=h.current;h.request(o,0,0,false,o.text);TextBoundsInfo info=new TextBoundsInfo();info.start=1;o.connection.finish(info);check(h.called==1&&h.actual==null,"outside returned range fallback");tests++;
h=new Fix4BoundsHarness();o=h.current;h.request(o,0,0,false,o.text);info=new TextBoundsInfo();info.left=Float.NaN;o.connection.finish(info);check(h.called==1&&h.actual==null,"non-finite fallback");tests++;
System.out.println("PASS "+tests+" asynchronous bounds cases; production request body executed");}}
'''
# Android interface boundaries only; the callback implementation is extracted unchanged.
o=w/'out/fix4-bounds-host';o.mkdir(parents=True,exist_ok=True);(o/'Fix4BoundsHarness.java').write_text(template.replace('/* BODY */',body));(o/'DisplayMetrics.java').write_text('package android.util; public class DisplayMetrics {public int widthPixels=1050,heightPixels=2376;}')
subprocess.run(['javac','-encoding','UTF-8','-d',str(o),str(o/'DisplayMetrics.java'),str(o/'Fix4BoundsHarness.java')],cwd=w,check=True)
subprocess.run(['java','-cp',str(o),'Fix4BoundsHarness'],cwd=w,check=True)
