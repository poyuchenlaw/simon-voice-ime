use std::process::Command;
fn main() {
    println!("cargo:rerun-if-changed=generate.py");
    println!("cargo:rerun-if-changed=../../app/src/phone/assets/zhuyin_initials.db");
    println!("cargo:rerun-if-changed=rime_single_chars.tsv");
    println!("cargo:rerun-if-changed=../../app/src/phone/assets/rime/build/terra_pinyin.table.bin");
    assert!(Command::new("python3").arg("generate.py").status().expect("inventory generator").success());
}
