package com.simon.voiceime;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The subset of Draft 2020-12 used by the pinned sentence v1 contract. Fail closed. */
final class SentenceContract {
    private SentenceContract() {}
    static void check(JSONObject schema, String definition, Object value) {
        try {
        checkNode(schema, schema.getJSONObject("$defs").getJSONObject(definition), value);

        } catch(Exception invalid){throw new IllegalArgumentException("sentence contract",invalid);}
    }
    static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("sentence contract"); }
    static int scalars(String value) {
        for (int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if (Character.isHighSurrogate(c)) { require(i+1<value.length() && Character.isLowSurrogate(value.charAt(++i))); }
            else require(!Character.isLowSurrogate(c));
        }
        return value.codePointCount(0,value.length());
    }
    static boolean integer(Object v) {
        return v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte || v instanceof java.math.BigInteger;
    }
    private static boolean accepts(JSONObject root, JSONObject rule, Object v) {
        try { checkNode(root,rule,v);return true; } catch(Exception invalid) { return false; }
    }
    private static void checkNode(JSONObject root, JSONObject s, Object v) throws Exception {
        if (s.has("$ref")) { checkNode(root,root.getJSONObject("$defs").getJSONObject(s.getString("$ref").substring(8)),v);return; }
        if(s.has("const")) require(s.get("const").equals(v));
        if(s.has("enum")) { boolean hit=false;JSONArray a=s.getJSONArray("enum");for(int i=0;i<a.length();i++)if(a.get(i).equals(v))hit=true;require(hit); }
        if(s.has("type")) switch(s.getString("type")) {
            case "object": require(v instanceof JSONObject);break;
            case "array": require(v instanceof JSONArray);break;
            case "string": require(v instanceof String);break;
            case "integer": require(integer(v));break;
            case "number": require(v instanceof Number && Double.isFinite(((Number)v).doubleValue()));break;
            case "boolean": require(v instanceof Boolean);break;
            case "null": require(v==JSONObject.NULL);break;
            default: throw new IllegalArgumentException("unsupported schema");
        }
        if(v instanceof JSONObject) {
            JSONObject obj=(JSONObject)v,props=s.optJSONObject("properties");JSONArray req=s.optJSONArray("required");
            if(req!=null)for(int i=0;i<req.length();i++)require(obj.has(req.getString(i)));
            if(props!=null)for(Iterator<String> iterator=obj.keys();iterator.hasNext();) {
                String key=iterator.next();
                if(props.has(key))checkNode(root,props.getJSONObject(key),obj.get(key));
                else if(s.has("additionalProperties"))require(s.getBoolean("additionalProperties"));
            }
        }
        if(v instanceof JSONArray) {
            JSONArray a=(JSONArray)v;require(a.length()>=s.optInt("minItems",0) && a.length()<=s.optInt("maxItems",Integer.MAX_VALUE));
            if(s.has("items"))for(int i=0;i<a.length();i++)checkNode(root,s.getJSONObject("items"),a.get(i));
        }
        if(v instanceof String) {
            String str=(String)v;int n=scalars(str);require(n>=s.optInt("minLength",0)&&n<=s.optInt("maxLength",Integer.MAX_VALUE));
            if(s.has("pattern"))require(java.util.regex.Pattern.compile(s.getString("pattern")).matcher(str).find());
        }
        if(v instanceof Number) {
            double n=((Number)v).doubleValue();require(Double.isFinite(n));
            if(s.has("minimum"))require(n>=s.getDouble("minimum"));
            if(s.has("maximum"))require(n<=s.getDouble("maximum"));
            if(s.has("exclusiveMaximum"))require(n<s.getDouble("exclusiveMaximum"));
        }
        if(s.has("anyOf")) { boolean hit=false;JSONArray a=s.getJSONArray("anyOf");for(int i=0;i<a.length();i++)if(accepts(root,a.getJSONObject(i),v))hit=true;require(hit); }
        if(s.has("allOf")) { JSONArray a=s.getJSONArray("allOf");for(int i=0;i<a.length();i++)checkNode(root,a.getJSONObject(i),v); }
        if(s.has("not"))require(!accepts(root,s.getJSONObject("not"),v));
        if(s.has("if")&&accepts(root,s.getJSONObject("if"),v))checkNode(root,s.getJSONObject("then"),v);
    }
    static JSONObject parse(String body, int maxBytes) {
        try {
        require(body.getBytes(StandardCharsets.UTF_8).length<=maxBytes);scalars(body);
        return new Wire(body).objectRoot();

        } catch(Exception invalid){throw new IllegalArgumentException("sentence contract",invalid);}
    }
    // Android's org.json is permissive and silently replaces duplicate keys.
    // Parse strict JSON before using it, identically on host and Android.
    private static final class Wire {
        final String text;int at,depth;
        Wire(String text){this.text=text;}
        void space(){while(at<text.length()&&" \t\n\r".indexOf(text.charAt(at))>=0)at++;}
        char peek(){space();require(at<text.length());return text.charAt(at);}
        void take(char c){require(peek()==c);at++;}
        JSONObject objectRoot() throws Exception {Object v=value();space();require(at==text.length()&&v instanceof JSONObject);return (JSONObject)v;}
        Object value() throws Exception {require(++depth<=16);char c=peek();Object result;
            if(c=='{'){at++;JSONObject obj=new JSONObject();space();if(peek()=='}'){at++;result=obj;}else{
                while(true){String key=string();require(!obj.has(key));take(':');obj.put(key,value());char next=peek();at++;if(next=='}')break;require(next==',');}result=obj;}}
            else if(c=='['){at++;JSONArray a=new JSONArray();if(peek()==']'){at++;}else{while(true){a.put(value());char next=peek();at++;if(next==']')break;require(next==',');}}result=a;}
            else if(c=='"')result=string();
            else {int start=at;while(at<text.length()&&",]} \t\r\n".indexOf(text.charAt(at))<0)at++;String token=text.substring(start,at);
                if("null".equals(token))result=JSONObject.NULL;
                else if("true".equals(token))result=Boolean.TRUE;else if("false".equals(token))result=Boolean.FALSE;
                else {require(token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"));
                    if(token.indexOf('.')>=0||token.indexOf('e')>=0||token.indexOf('E')>=0){double n=Double.parseDouble(token);require(Double.isFinite(n));result=n;}
                    else {long n=Long.parseLong(token);result=n>=Integer.MIN_VALUE&&n<=Integer.MAX_VALUE?(Object)(int)n:(Object)n;}}}
            depth--;return result;
        }
        String string() throws Exception {take('"');int start=at-1;boolean closed=false;
            while(at<text.length()){char c=text.charAt(at++);require(c>=32);if(c=='"'){closed=true;break;}
                if(c=='\\'){require(at<text.length());char escape=text.charAt(at++);require("\"\\/bfnrtu".indexOf(escape)>=0);
                    if(escape=='u'){require(at+4<=text.length());for(int i=0;i<4;i++)require(Character.digit(text.charAt(at++),16)>=0);}}}
            require(closed);Object v=new JSONTokener(text.substring(start,at)).nextValue();require(v instanceof String);scalars((String)v);return (String)v;
        }
    }
}
