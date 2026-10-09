use zhuyin_t9_core::{Core, encode, check};
fn run()->Result<(),String> {
    let args:Vec<String>=std::env::args().collect();let cmd=args.get(1).map(String::as_str).unwrap_or("");
    let text=args.get(2).map(String::as_str).unwrap_or("");
    match cmd {
        "gen"=>{let status=std::process::Command::new("python3").arg(concat!(env!("CARGO_MANIFEST_DIR"),"/generate.py")).status().map_err(|e|e.to_string())?;if !status.success(){return Err("generation failed".into());}}
        "encode"=>println!("{}",encode(text)?.iter().map(u16::to_string).collect::<Vec<_>>().join(" ")),
        "check"=>{let codes=args.get(3).ok_or("missing codes")?.split_whitespace().map(str::parse).collect::<Result<Vec<u16>,_>>().map_err(|e|e.to_string())?;println!("{:?}",check(text,&codes));}
        "fsm-replay"=>{let mut core=Core::new();let mut stars=0;let mut n=0;
            for code in text.split_whitespace(){if n>0 {let next=code.as_bytes()[0]-b'0';let old=core.snapshot();let extends=core.push(next).is_ok();let count=core.sequence().split_whitespace().count();core.restore(&old).map_err(str::to_string)?;
                if !extends || count==n {core.boundary().map_err(str::to_string)?;stars+=1;}}
                for k in code.bytes(){if !k.is_ascii_digit(){return Err("non digit code".into());}core.push(k-b'0').map_err(str::to_string)?;}if !core.is_complete(){return Err(format!("incomplete {code}"));}n+=1;
            }let want=text.split_whitespace().map(str::parse).collect::<Result<Vec<u16>,_>>().map_err(|e|e.to_string())?;
            if core.codes()!=want{return Err("segmentation mismatch".into());}
            println!("{{\"accepted\":true,\"chars\":{n},\"stars\":{stars}}}");}
        _=>return Err("use gen|encode <text>|check <sentence> <codes>|fsm-replay <codes>".into())
    }Ok(())
}
fn main(){if let Err(e)=run(){eprintln!("{e}");std::process::exit(1);}}
