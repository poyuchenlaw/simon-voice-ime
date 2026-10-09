use std::sync::OnceLock;
#[derive(Default)]
struct Node { digits:String, next:[Option<usize>;10], complete:bool }
struct Inventory { nodes:Vec<Node>, syllables:Vec<(&'static str,u16)>, readings:Vec<(char,usize)> }
impl Inventory {
 fn readings_at(&self,pos:usize)->impl Iterator<Item=(u16,&'static str,u32)>+'_ {
  let bytes=include_bytes!("../generated/char_readings.bin");
  (0..bytes[pos+4] as usize).map(move |i| {let p=pos+5+i*6;let index=u16::from_le_bytes(bytes[p..p+2].try_into().unwrap()) as usize;let freq=u32::from_le_bytes(bytes[p+2..p+6].try_into().unwrap());let (syllable,code)=self.syllables[index];(code,syllable,freq)})
 }
 fn reading_pos(&self,ch:char)->Option<usize> {self.readings.binary_search_by_key(&ch,|r|r.0).ok().map(|i|self.readings[i].1)}
}
static INVENTORY:OnceLock<Inventory>=OnceLock::new();
fn inventory()->&'static Inventory { INVENTORY.get_or_init(|| {
    let mut nodes=vec![Node::default()];
    for line in include_str!("../generated/legal_syllables.tsv").lines().skip(1) {
        let digits=line.split('\t').nth(1).unwrap(); let mut at=0;
        for digit in digits.bytes() { let k=(digit-b'0') as usize;
            let next=match nodes[at].next[k] {Some(i)=>i,None=>{let i=nodes.len();let s=format!("{}{}",nodes[at].digits,k);nodes.push(Node{digits:s,..Default::default()});nodes[at].next[k]=Some(i);i}};at=next;
        } nodes[at].complete=true;
    }
    assert!(nodes.len()<=128);
    let bytes=include_bytes!("../generated/char_readings.bin"); assert_eq!(&bytes[..4],b"T9C2");
    let syllables:Vec<_>=include_str!("../generated/legal_syllables.tsv").lines().skip(1).map(|l|{let mut x=l.split('\t');(x.next().unwrap(),x.next().unwrap().parse::<u16>().unwrap())}).collect();
    let mut readings=Vec::new();let mut pos=4;
    while pos<bytes.len() {let ch=char::from_u32(u32::from_le_bytes(bytes[pos..pos+4].try_into().unwrap())).unwrap();let n=bytes[pos+4] as usize;readings.push((ch,pos));pos+=5+6*n;}
    readings.shrink_to_fit();Inventory{nodes,syllables,readings}
}) }
#[derive(Clone,Default)]
pub struct Core { state:u128, committed:Vec<u16>, history:Vec<u8> }
impl Core {
    pub fn new()->Self {inventory();Self{state:1,..Default::default()}}
    fn at(&self)->usize {self.state.trailing_zeros() as usize}
    pub fn legal_next_keys(&self)->u16 {
        let nodes=&inventory().nodes;let node=&nodes[self.at()];let mut mask=0;
        for k in 0..10 { if node.next[k].is_some() || node.complete && nodes[0].next[k].is_some() {mask|=1<<k;} }
        // SPEC acceptance 47 forbids repeated medial without explicit '*'.
        if node.digits.ends_with('7') {mask &= !(1<<7);}
        if node.complete {mask|=1<<10;}mask
    }
    pub fn push(&mut self,k:u8)->Result<(), &'static str> {
        if k>10 || self.legal_next_keys() & (1<<k)==0 {return Err("illegal key");}
        let nodes=&inventory().nodes;let at=self.at();
        if k==10 {self.committed.push(nodes[at].digits.parse().unwrap());self.state=1;}
        else if let Some(next)=nodes[at].next[k as usize] {self.state=1<<next;}
        else {self.committed.push(nodes[at].digits.parse().unwrap());self.state=1<<nodes[0].next[k as usize].unwrap();}
        self.history.push(k);Ok(())
    }
    pub fn boundary(&mut self)->Result<(), &'static str> {self.push(10)}
    pub fn backspace(&mut self) {let mut h=self.history.clone();h.pop();self.restore(&h).expect("valid history");}
    pub fn codes(&self)->Vec<u16> {let mut codes=self.committed.clone();let n=&inventory().nodes[self.at()];if n.complete {codes.push(n.digits.parse().unwrap());}codes}
    pub fn sequence(&self)->String {let mut out:Vec<String>=self.committed.iter().map(u16::to_string).collect();let n=&inventory().nodes[self.at()];if !n.digits.is_empty(){out.push(n.digits.clone());}out.join(" ")}
    pub fn is_complete(&self)->bool {self.state==1&&!self.committed.is_empty() || inventory().nodes[self.at()].complete}
    pub fn is_closed(&self)->bool {let n=&inventory().nodes[self.at()];n.complete&&n.next.iter().all(Option::is_none)}
    pub fn snapshot(&self)->Vec<u8> {self.history.clone()}
    pub fn restore(&mut self,bytes:&[u8])->Result<(), &'static str> {if bytes.len()>16384{return Err("snapshot too large");}let mut fresh=Core::new();for &k in bytes {fresh.push(k)?;}*self=fresh;Ok(())}
}
pub fn legal_codes()->Vec<String> {inventory().nodes.iter().filter(|n|n.complete).map(|n|n.digits.clone()).collect()}
pub fn encode(text:&str)->Result<Vec<u16>,String> {text.chars().filter(|c|is_han(*c)).map(|c| inventory().reading_pos(c).map(|p|inventory().readings_at(p).next().unwrap().0).ok_or_else(||format!("unattested character U+{:X}",c as u32))).collect()}
pub fn is_han(c:char)->bool {matches!(c as u32,0x3400..=0x9fff|0x20000..=0x323af|0xf900..=0xfaff)}
pub fn check(text:&str,codes:&[u16])->Vec<bool> {let chars:Vec<char>=text.chars().collect();if chars.len()!=codes.len(){return vec![false;chars.len()];}chars.iter().zip(codes).map(|(ch,code)|inventory().reading_pos(*ch).is_some_and(|p|inventory().readings_at(p).any(|r|r.0==*code))).collect()}
// The Android JNI C bridge calls these exports; strings cross JNI as UTF-8 byte arrays.
#[no_mangle] pub extern "C" fn t9_create()->*mut Core {Box::into_raw(Box::new(Core::new()))}
#[no_mangle] pub unsafe extern "C" fn t9_destroy(p:*mut Core) {if !p.is_null(){drop(Box::from_raw(p));}}
#[no_mangle] pub unsafe extern "C" fn t9_push(p:*mut Core,k:u8)->bool {p.as_mut().is_some_and(|c|c.push(k).is_ok())}
#[no_mangle] pub unsafe extern "C" fn t9_mask(p:*const Core)->u16 {p.as_ref().map_or(0,Core::legal_next_keys)}
#[no_mangle] pub unsafe extern "C" fn t9_backspace(p:*mut Core) {if let Some(c)=p.as_mut(){c.backspace();}}
#[no_mangle] pub unsafe extern "C" fn t9_complete(p:*const Core)->bool {p.as_ref().is_some_and(Core::is_complete)}
#[no_mangle] pub unsafe extern "C" fn t9_closed(p:*const Core)->bool {p.as_ref().is_some_and(Core::is_closed)}
#[no_mangle] pub unsafe extern "C" fn t9_copy(p:*const Core,kind:u8,out:*mut u8,cap:usize)->usize {let Some(c)=p.as_ref() else{return 0};let data=if kind==0{c.sequence().into_bytes()}else{c.snapshot()};if cap>=data.len()&&!out.is_null(){std::ptr::copy_nonoverlapping(data.as_ptr(),out,data.len());}data.len()}
#[no_mangle] pub unsafe extern "C" fn t9_restore(p:*mut Core,data:*const u8,n:usize)->bool {if data.is_null()&&n>0{return false;}p.as_mut().is_some_and(|c|c.restore(if n==0{&[]}else{std::slice::from_raw_parts(data,n)}).is_ok())}
#[no_mangle] pub unsafe extern "C" fn t9_check(text:*const u8,n:usize,codes:*const u16,m:usize,out:*mut u8)->usize {if text.is_null()||codes.is_null(){return 0;}let Ok(s)=std::str::from_utf8(std::slice::from_raw_parts(text,n))else{return 0};let result=check(s,std::slice::from_raw_parts(codes,m));if !out.is_null(){for (i,v) in result.iter().enumerate(){*out.add(i)=u8::from(*v);}}result.len()}

pub fn syllables(code:u16)->Vec<(String,f64)> {
 include_str!("../generated/code_to_syllables.tsv").lines().skip(1).find_map(|l| {let a:Vec<_>=l.split('\t').collect();if a[0].parse::<u16>().ok()!=Some(code){return None;}Some(a[1].split_whitespace().zip(a[2].split_whitespace()).map(|(s,f)|(s.to_string(),f.parse().unwrap())).collect())}).unwrap_or_default()
}
pub fn reading_for(ch:char,code:u16)->Option<String> {inventory().readings_at(inventory().reading_pos(ch)?).find(|r|r.0==code).map(|r|r.1.to_string())}
pub fn characters(code:u16)->String {let mut chars:Vec<_>=inventory().readings.iter().filter_map(|(ch,pos)|inventory().readings_at(*pos).filter(|r|r.0==code).map(|r|r.2).max().map(|f|(*ch,f))).collect();chars.sort_by_key(|(c,f)|(std::cmp::Reverse(*f),*c));chars.iter().take(200).map(|(c,_)|*c).collect()}
#[no_mangle] pub unsafe extern "C" fn t9_data(kind:u8,code:u16,ch:u32,out:*mut u8,cap:usize)->usize {
 let data=match kind {0=>include_str!("../generated/code_to_syllables.tsv").to_string(),1=>characters(code),_=>char::from_u32(ch).and_then(|c|reading_for(c,code)).unwrap_or_default()}.into_bytes();
 if cap>=data.len()&&!out.is_null(){std::ptr::copy_nonoverlapping(data.as_ptr(),out,data.len());}data.len()
}
