use zhuyin_t9_core::{Core, check, encode};
#[test]
fn spec_examples() {
    assert_eq!(encode("聲請條款").unwrap(), vec![50,470,279,370]);
    assert_eq!(check("聲請條款", &[50,470,279,370]),vec![true;4]);
    assert_eq!(check("聲請", &[50]),vec![false;2]);
    let mut c=Core::new(); c.push(4).unwrap(); assert_eq!(c.legal_next_keys(),1<<7);
    c.push(7).unwrap(); assert_eq!(c.legal_next_keys() & (1<<7),0);
    for k in [8,9,0,10] { assert_ne!(c.legal_next_keys() & (1<<k),0); }
    let snap=c.snapshot(); let mut d=Core::new();d.restore(&snap).unwrap();assert_eq!(d.snapshot(),snap);
    c.boundary().unwrap(); c.push(7).unwrap(); assert_eq!(c.codes(),vec![47,7]);
}
#[test]
fn ranked_readings() {
    let items=zhuyin_t9_core::syllables(0);
    assert_eq!(items[0].0,"ㄦ");
    assert!(items.windows(2).all(|w|w[0].1>=w[1].1));
    assert_eq!(zhuyin_t9_core::reading_for('妬',27),Some("ㄉㄨ".to_string()));
}
