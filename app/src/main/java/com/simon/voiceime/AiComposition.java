package com.simon.voiceime;

/** Keeps the original native session and controller untouched until undo or the next edit. */
final class AiComposition {
    /** Tracks a reverted key span through unrelated insertions/deletions. */
    static final class RevertedSpan {
        private String keys;private int start,end;private boolean active=true;
        RevertedSpan(String keys,int start,int end){this.keys=keys;this.start=start;this.end=end;}
        void edited(String next){
            if(!active||keys.equals(next))return;
            int a=0,z=0;while(a<keys.length()&&a<next.length()&&keys.charAt(a)==next.charAt(a))a++;
            while(z<keys.length()-a&&z<next.length()-a&&keys.charAt(keys.length()-1-z)==next.charAt(next.length()-1-z))z++;
            int b=keys.length()-z,delta=next.length()-keys.length();
            if(a<end&&b>start||a==b&&a>=start&&a<end)active=false;
            else if(b<=start){start+=delta;end+=delta;}
            keys=next;
        }
        boolean blocks(String current){edited(current);return active;}
    }
    private ZhuyinInputController before,after;
    private long appliedAt=-1;
    boolean tooRecent(long now){return automatic()&&now-appliedAt<800;}
    boolean automatic(){return appliedAt>=0;}
    ZhuyinInputController applyAuto(String keys,String text,long now,boolean enabled,boolean protectedToken){
        if(!enabled||protectedToken)return null;
        ZhuyinInputController result=apply(keys,text);if(result!=null)appliedAt=now;return result;
    }
    ZhuyinInputController beforeEnter(ZhuyinInputController current,long now){
        return automatic()&&now-appliedAt<800?undo(current):current;
    }
    AiComposition(ZhuyinInputController before){this.before=before;}
    ZhuyinInputController apply(String keys,String text){
        if(before==null||after!=null)return null;
        after=before.preparedSentence(keys,text);return after;
    }
    ZhuyinInputController undo(ZhuyinInputController current){
        if(before==null||after==null||current!=after)return null;
        ZhuyinInputController restored=before;after.close();before=null;after=null;return restored;
    }
    static boolean protectedChange(String old,String text,Iterable<String> installed){
        int prefix=0,suffix=0;while(prefix<old.length()&&prefix<text.length()&&old.charAt(prefix)==text.charAt(prefix))prefix++;
        while(suffix<old.length()-prefix&&suffix<text.length()-prefix&&old.charAt(old.length()-1-suffix)==text.charAt(text.length()-1-suffix))suffix++;
        String changed=old.substring(prefix,old.length()-suffix)+text.substring(prefix,text.length()-suffix);
        if(changed.codePoints().anyMatch(cp->cp<128&&!Character.isWhitespace(cp)||Character.getType(cp)==Character.OTHER_PUNCTUATION||Character.getType(cp)==Character.DASH_PUNCTUATION||Character.getType(cp)==Character.CONNECTOR_PUNCTUATION))return true;
        if(changed.matches("(?s).*[0-9零〇一二三四五六七八九十百千萬億兆兩壹貳參肆伍陸柒捌玖拾佰仟元圓塊角分年月日時點秒條項款目號度字第不無未非否免勿毋沒別得應須可能會].*"))return true;
        for(String value:new String[]{old,text}){
            java.util.regex.Matcher date=java.util.regex.Pattern.compile("今天|明天|昨天|上午|下午|早上|晚上|週[一二三四五六日]|星期[一二三四五六日]").matcher(value);
            while(date.find())if(date.start()<value.length()-suffix&&date.end()>prefix)return true;
        }
        for(String word:installed)for(String value:new String[]{old,text}){
            int pos=-1;while((pos=value.indexOf(word,pos+1))>=0)if(pos<value.length()-suffix&&pos+word.length()>prefix)return true;
        }
        return false;
    }
    void discard(){if(before!=null&&after!=null)before.close();before=null;after=null;}
}
