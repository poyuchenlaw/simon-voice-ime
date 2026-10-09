use zhuyin_t9_core::{Core,legal_codes,check,encode};
#[test]
fn inventory_prefix_property_and_snapshots() {
 let codes=legal_codes();
 for code in &codes {
  let mut c=Core::new();for k in code.bytes(){c.push(k-b'0').unwrap();}
  assert!(c.is_complete());assert_eq!(c.codes(),vec![code.parse::<u16>().unwrap()]);
  c.boundary().unwrap();assert_eq!(c.codes(),vec![code.parse::<u16>().unwrap()]);
  let snap=c.snapshot();let mut d=Core::new();d.restore(&snap).unwrap();assert_eq!(d.codes(),c.codes());
  d.backspace();assert!(d.is_complete());
 }
 // Exhaust every key prefix, including unreachable strings, against a brute inventory oracle.
 for len in 0..=3 {for number in 0..10_usize.pow(len) {
  let s=if len==0{String::new()}else{format!("{:0width$}",number,width=len as usize)};
  let mut c=Core::new();let mut valid=true;for k in s.bytes(){if c.push(k-b'0').is_err(){valid=false;break;}}
  if !valid {continue;}
  let sequence=c.sequence();let prefix=sequence.split_whitespace().last().unwrap_or("");
  let complete=codes.iter().any(|x|x==prefix);let mut mask=0;
  for k in 0..10 {let extension=format!("{prefix}{k}");if codes.iter().any(|x|x.starts_with(&extension)) || complete&&codes.iter().any(|x|x.starts_with(&k.to_string())){mask|=1<<k;}}
  if prefix.ends_with('7'){mask&=!(1<<7);}if complete{mask|=1<<10;}
  assert_eq!(c.legal_next_keys(),mask,"prefix {s}");
 }}
}
#[test]
fn fifty_hand_character_checks() {
 // Hand fixtures from Taiwan Zhuyin, independent of the generated lookup.
 let cases=[("聲",50),("請",470),("條",279),("款",370),("八",18),("波",18),("貓",19),("飛",19),("天",270),("多",278),("你",27),("來",29),("高",39),("可",38),("好",39),("家",478),("氣",47),("小",479),("知",5),("車",58),("水",579),("日",5),("字",6),("次",6),("四",6),("一",7),("五",7),("魚",7),("阿",8),("喔",8),("鵝",8),("欸",9),("愛",9),("黑",39),("凹",9),("歐",9),("安",0),("恩",0),("昂",0),("而",0),("想",470),("明",170),("房",10),("法",18),("我",78),("們",10),("中",570),("國",378),("人",50),("這",58)];
 assert_eq!(cases.len(),50);
 for (ch,code) in cases {assert_eq!(check(ch,&[code]),vec![true],"{ch}={code}");assert_eq!(check(ch,&[999]),vec![false]);}
 assert_eq!(check("😀",&[50]),vec![false]);assert!(encode("😀").unwrap().is_empty());
 let mut c=Core::new();c.push(4).unwrap();let before=c.snapshot();assert!(c.restore(&[4,0]).is_err());assert_eq!(c.snapshot(),before);
}
