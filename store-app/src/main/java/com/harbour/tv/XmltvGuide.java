package com.harbour.tv;

import android.util.Xml;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.xmlpull.v1.XmlPullParser;

/** Bounded XMLTV reader; exact tvg-id mapping and explicit timezone offsets. */
final class XmltvGuide {
  static final class Programme {
    final String title; final long start,end;
    Programme(String title,long start,long end){this.title=title;this.start=start;this.end=end;}
  }
  static long timestamp(String value){
    if(value==null||!value.matches("[0-9]{14} [+-][0-9]{4}"))throw new IllegalArgumentException("XMLTV 時間缺少時區或秒數");
    return OffsetDateTime.parse(value,DateTimeFormatter.ofPattern("uuuuMMddHHmmss xx").withResolverStyle(java.time.format.ResolverStyle.STRICT)).toEpochSecond();
  }
  static List<Programme> parse(String xml,String channel) throws Exception {
    if(xml.length()>8*1024*1024||xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")||xml.toUpperCase(Locale.ROOT).contains("<!ENTITY"))throw new IOException("節目表格式或大小不支援");
    if(channel==null||channel.trim().isEmpty())throw new IOException("來源缺少 tvg-id，無法對應節目");
    XmlPullParser p=Xml.newPullParser();p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,false);p.setInput(new StringReader(xml));
    List<Programme> result=new ArrayList<>();boolean selected=false;String title="";long start=0,end=0;int count=0;
    for(int event=p.getEventType();event!=XmlPullParser.END_DOCUMENT;event=p.next()) {
      if(event==XmlPullParser.START_TAG&&p.getName().equals("programme")){
        if(++count>200000)throw new IOException("節目表項目過多");
        selected=channel.equals(p.getAttributeValue(null,"channel"));title="";
        if(selected)try{start=timestamp(p.getAttributeValue(null,"start"));end=timestamp(p.getAttributeValue(null,"stop"));}catch(RuntimeException invalid){selected=false;}
      } else if(event==XmlPullParser.START_TAG&&selected&&p.getName().equals("title")){String found=p.nextText();if(title.isEmpty())title=found;}
      else if(event==XmlPullParser.END_TAG&&p.getName().equals("programme")){
        if(selected&&end>start&&!title.isEmpty())result.add(new Programme(title,start,end));selected=false;
      }
    }
    result.sort(Comparator.comparingLong(v->v.start));return result;
  }
}
