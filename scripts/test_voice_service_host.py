#!/usr/bin/env python3
"""Compile exact service method bodies against small host fakes (no Android runtime).
This is a component integration seam, not an Activity/Looper/device simulation.
No production body is rewritten; generated source and extraction SHA are evidence.
"""
import argparse, hashlib, json, pathlib, re, subprocess
p=argparse.ArgumentParser();p.add_argument('--root',default=str(pathlib.Path(__file__).resolve().parents[1]));p.add_argument('--out',required=True);p.add_argument('--baseline-applicable',action='store_true');p.add_argument('--queue-only',action='store_true');p.add_argument('--baseline-r5',action='store_true');p.add_argument('--method');p.add_argument('--baseline-r7',action='store_true');p.add_argument('--baseline-r8',action='store_true');p.add_argument('--baseline-r9',action='store_true');args=p.parse_args()
root=pathlib.Path(args.root);out=pathlib.Path(args.out).resolve();out.mkdir(parents=True,exist_ok=True)
src=(root/'app/src/main/java/com/simon/voiceime/SimonIMEService.java').read_text()
def block(start,text=None):
    if text is None:text=src
    opening=text.index('{',start);level=0
    # Ignore comments/string/char braces while keeping original bytes.
    token=re.compile(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]',re.S)
    for m in token.finditer(text,opening):
        if m.group()=='{':level+=1
        elif m.group()=='}':
            level-=1
            if level==0:return text[start:m.end()]
    raise ValueError('unclosed block')
names=['fetchServerArchiveText','commitFinalText','pasteClipboardText','copyToSystemClipboard','reserveUtteranceGeneration','completeReservedUtteranceWithText','completeReservedUtteranceWithoutText','collectReadyUtteranceCommitsLocked','handleWTIResponse','sendFullAudioHttpFallback','finalizeStreamingSession','drainPendingVoiceQueue','schedulePendingDrain','markPendingGeneration','handleAiCommandResponse','sendAiCommand','sendAiCommandAudio','commitReadyUtterance','consumeSilentResult','voiceAudioDurationMs','keepPendingModeResult','showNoVoiceStatus','deliverVoiceResult','isDiscardedVoiceGeneration','persistRecordingRead','finishDurableRecording','discardProtectedRecording','readAndPersistRecordingAudio','pcmWavBody','durableOrMemoryAudioBody','rescueReplaceAudio']
names.extend(['receiveAudioReceipt','sendAudioEndOfStream','finishWhenRecorderStopped','sendToWTI','notePendingGeneration','httpFallbackFullAudio','runOfflineFullAudioFallback','sendTextProcess','completeAppendProcessTextFailureWithOfflineFallback'])
parts=[]
counts={}
# These production APIs do not exist in the work order's requested old revision.
baseline_absent={'schedulePendingDrain','markPendingGeneration','commitReadyUtterance','consumeSilentResult','voiceAudioDurationMs','keepPendingModeResult','showNoVoiceStatus','deliverVoiceResult','isDiscardedVoiceGeneration','persistRecordingRead','finishDurableRecording','discardProtectedRecording','durableOrMemoryAudioBody'}
baseline_absent.add('finishWhenRecorderStopped')
if args.baseline_applicable:
    revision=subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()
    assert revision.startswith('bf426d8'), 'baseline exemptions apply only to bf426d8'
r5_absent={'voiceAudioDurationMs','keepPendingModeResult'}
if args.baseline_r5:
    original=subprocess.check_output(['git','-C',str(root),'show','9a681dc:app/src/main/java/com/simon/voiceime/SimonIMEService.java'])
    assert hashlib.sha256(original).hexdigest()==hashlib.sha256(src.encode()).hexdigest(), 'r5 baseline must be exactly parent 9a681dc'
if args.baseline_r9:
    repo=pathlib.Path(__file__).resolve().parents[1]
    original=subprocess.check_output(['git','-C',str(repo),'show','c17c37b:app/src/main/java/com/simon/voiceime/SimonIMEService.java'])
    assert hashlib.sha256(original).hexdigest()==hashlib.sha256(src.encode()).hexdigest(), 'r9 baseline must be exact c17c37b'
for name in names:
    if name=='readAndPersistRecordingAudio':continue # Explicit recorder-slice wrapper below.
    matches=list(re.finditer(r'(?:private|protected)\s+(?:(?:static|synchronized)\s+)?(?:void|boolean|long|String|RequestBody)\s+'+name+r'\s*\(',src))
    counts[name]=len(matches)
    assert matches or (args.baseline_applicable and name in baseline_absent) or (args.baseline_r5 and name in r5_absent) or (args.baseline_r9 and name=='fetchServerArchiveText'),f'production method extraction missing: {name}'
    parts.extend(block(m.start()) for m in matches)
ready=re.search(r'private static final class ReadyUtterance\s*',src)
assert ready or args.baseline_applicable, 'production ReadyUtterance class missing'
if ready:parts.append(block(ready.start()))
# Execute the actual WS final lambda, selected by its explicit callback boundary.
start=src.index('String finalText = json.optString("text", "");',src.index('} else if ("final".equals(type))'))
end=src.index('Log.i(TAG, "[AudioStream] 最終文字:',start)
parts.append('void wsFinal(JSONObject json,int myGen) {\n'+src[start:end]+'\n}')
# One real recorder-loop iteration, bound to a fake recorder. No production statements rewritten.
record_start=src.index('                int read;',src.index('while (isRecording)'))
record_end=src.index('                long voiceNow=',record_start)
record_slice=src[record_start:record_end]
# A break is the production nonpositive-read exit. This wrapper returns zero in
# that case; positive reads use the exact original statements, including the tail.
assert not re.search(r'\bcontinue\s*;',record_slice), 'recorder slice gained continue; wrapper semantics need review'
parts.append('int readAndPersistRecordingAudio(AudioRecord audioRecord,byte[] buffer,String recordingSessionId){do {\n'+record_slice+'return read;}while(false);return 0;}')
# Exact PCM stream write range, including the old skipped prefix branch.
stream_start=src.index('                if (read > 0) {',record_end)
stream_end=src.index('                    // v6.1: APPEND',stream_start)
parts.append('void streamCapturedRead(byte[] buffer,int read){do {\n'+src[stream_start:stream_end]+'} }while(false);}')
# Execute lifecycle entry statements; UI cleanup outside this slice is Android-only.
for callback,signature in [('onFinishInputView','boolean finishingInput'),('onDestroy','')]:
    match=re.search(r'public void '+callback+r'\([^)]*\)\s*\{',src)
    end=src.index('        dismissSymbolPopup();',match.end())
    parts.append('void '+callback+'('+signature+'){'+src[match.end():end]+'}')
for callback in ['onLowMemory','onTrimMemory']:
    match=re.search(r'public void '+callback+r'\([^)]*\)\s*\{',src)
    body=block(match.start()) if match else None
    if body:parts.append(body.replace('public void','void').replace('super.'+callback+'();','').replace('super.'+callback+'(level);',''))
    else:parts.append('void '+callback+'('+('int level' if callback=='onTrimMemory' else '')+'){}')
helper=re.search(r'private void flushPendingVoiceAudio\s*\(',src)
if helper:parts.append(block(helper.start()))
init=re.search(r'voicePendingQueue = VoicePendingQueue.getInstance\(getFilesDir\(\),SAMPLE_RATE\);',src)
assert init or args.baseline_applicable, 'production singleton initialization missing'
if init:parts.append('void enterIme(){'+init.group()+'}')
start_line=re.search(r'activePendingSessionId=\(voicePendingQueue==null\|\|protectedInputField\)\?null:voicePendingQueue\.beginAsync\([^\n]+;',src)
assert start_line, 'production begin-recording session line missing'
parts.append('void beginRecordingSession(){'+start_line.group()+'}')
clear_line=re.search(r'if\(stoppedSessionId!=null&&stoppedSessionId\.equals\(activePendingSessionId\)\)activePendingSessionId=null;',src)
assert clear_line, 'production recorder-stop session release missing'
parts.append('void releaseActiveRecordingSession(String stoppedSessionId){'+clear_line.group()+'}')
(out/'extraction-counts.json').write_text(json.dumps({'methods':counts,'ready_class':bool(ready),'ime_singleton':bool(init),'recorder_slice_breaks':len(re.findall(r'\bbreak\s*;',record_slice))},indent=2))
template=(pathlib.Path(__file__).parent/'voice_service_host/VoiceServiceHarness.java.in').read_text()
if args.baseline_r8:
    repo=pathlib.Path(__file__).resolve().parents[1]
    original=subprocess.check_output(['git','-C',str(repo),'show','a979cf5:app/src/main/java/com/simon/voiceime/SimonIMEService.java'])
    assert hashlib.sha256(original).hexdigest()==hashlib.sha256(src.encode()).hexdigest(), 'r8 baseline must be exact a979cf5'
    absent=['serverDiscardDue','serverDiscardDelayMs','serverDiscardFailed','noteStreamedBytes','acceptServerCopyAfterWriteFailure','sealStreamedBytes']
    removed=[]
    for match in reversed(list(re.finditer(r'@Test public void (\w+)\(',template))):
        body=block(match.start(),template)
        if any(re.search(r'\.'+name+r'\(',body) for name in absent):
            removed.append(match.group(1));template=template[:match.start()]+template[match.start()+len(body):]
    (out/'not-applicable.json').write_text(json.dumps({'requires_new_api':removed},indent=2))
if args.baseline_r7:
    repo=pathlib.Path(__file__).resolve().parents[1]
    original=subprocess.check_output(['git','-C',str(repo),'show','1dd15b7:app/src/main/java/com/simon/voiceime/SimonIMEService.java'])
    assert hashlib.sha256(original).hexdigest()==hashlib.sha256(src.encode()).hexdigest(), 'r7 baseline must be exact 1dd15b7'
    match=re.search(r'@Test public void protectedServerDiscardRetriesAfterPhoneRecovery\(',template)
    if match:template=template.replace(block(match.start(),template),'')
    (out/'not-applicable.json').write_text(json.dumps({'protectedServerDiscardRetriesAfterPhoneRecovery':'new pending-discard API absent; authenticated discard behavior still tested'}))
if args.baseline_applicable:
    # Keep test bodies identical; remove only tests requiring an API absent at bf426d8.
    unavailable={
        'aiSuccessAndFailureKeepTheCorrectAudio':'three-argument AI callback absent',
        'aiSilenceDoesNotInsertOrWriteHistory':'three-argument AI callback absent',
        'protectedSessionLateModesNeverReachClipboard':'discard API and three-argument AI callback absent',
        'imeReentrySharesTheSameDiskQueue':'singleton factory absent',
        'recordingTailAfterStopPersistsBeforeCompletion':'async capture API absent',
        'protectedSwitchDuringRecordingDiscardsFinalReadAndLateResults':'protected discard and async capture API absent',
        'startAndAppendDoNotWaitForDiskExecutor':'async capture API absent',
        'stalledNetworkSinkDoesNotBlockDurableRecordingWrites':'async capture API absent',
        'serviceRestartRedeliversSavedTextWithoutResetOrUpload':'persistResult API absent',
        'protectedDiscardDuringQueuedUploadBlocksLateDelivery':'discardAsync and runIO APIs absent',
        'stopTimeoutWaitsForTailWithoutAnotherBlockingJoin':'finishWhenRecorderStopped API absent',
    }
    for name,reason in unavailable.items():
        match=re.search(r'@Test public void '+name+r'\(',template)
        if match:
            body=block(match.start(),template);template=template.replace(body,'')
    import json
    (out/'not-applicable.json').write_text(json.dumps(unavailable,indent=2))
generated=template.replace('/* SERVICE_METHODS */','\n\n'.join(parts))
java=out/'VoiceServiceHarness.java';java.write_text(generated)
(out/'extraction.sha256').write_text(hashlib.sha256(src.encode()).hexdigest()+' SimonIMEService.java\n'+hashlib.sha256(generated.encode()).hexdigest()+' VoiceServiceHarness.java\n')
def jar(group,artifact):
    files=list((pathlib.Path('/home/simon/.gradle/caches/modules-2/files-2.1')/group/artifact).glob('*/*/*.jar'))
    if not files:raise SystemExit(f'cached dependency missing: {group}/{artifact}')
    return str(sorted(files)[-1])
cp=':'.join([jar('junit','junit'),jar('org.hamcrest','hamcrest-core'),jar('org.json','json')])
(out/'resolved-classpath.txt').write_text(cp+'\n')
prod=root/'app/src/main/java/com/simon/voiceime'
files=[str(prod/n) for n in ['VoicePendingQueue.java','TextLossGuard.java']]
if (prod/'VoiceResultText.java').exists():files.append(str(prod/'VoiceResultText.java'))
test_class='VoiceServiceHarness'
if args.queue_only:
    repo=pathlib.Path(__file__).resolve().parents[1]
    tests=(repo/'app/src/test/java/com/simon/voiceime/VoicePendingQueueTest.java').read_text()
    queue=(prod/'VoicePendingQueue.java').read_text()
    unavailable={}
    for match in reversed(list(re.finditer(r'@Test public void (\w+)\(',tests))):
        body=block(match.start(),tests)
        absent=[name for name in ['markTranscribed','delivered','parseSuccessfulResponse','getInstance','flush','runIO','beginAsync','appendAsync','finishRecording'] if re.search(r'\.'+name+r'\(',body) and not re.search(r'\b'+name+r'\(',queue)]
        if 'VoiceResultText.' in body and not (prod/'VoiceResultText.java').exists():absent.append('VoiceResultText')
        if args.baseline_applicable and absent:
            unavailable[match.group(1)]=absent;tests=tests[:match.start()]+tests[match.start()+len(body):]
    import json
    (out/'queue-not-applicable.json').write_text(json.dumps(unavailable,indent=2))
    java=out/'VoicePendingQueueTest.java';java.write_text(tests);test_class='VoicePendingQueueTest'
subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(out),*files,str(java)],check=True)
if args.method:
    runner=out/'RunOne.java';runner.write_text('import org.junit.runner.*; public class RunOne {public static void main(String[] args)throws Exception {Result r=new JUnitCore().run(Request.method(Class.forName(args[0]),args[1]));for(var f:r.getFailures())System.out.println(f.getTrace());System.out.println("tests="+r.getRunCount()+" failures="+r.getFailureCount());System.exit(r.wasSuccessful()?0:1);}}')
    subprocess.run(['javac','-cp',cp,'-d',str(out),str(runner)],check=True)
    command=['java','-cp',str(out)+':'+cp,'RunOne','com.simon.voiceime.'+test_class,args.method]
else:command=['java','-cp',str(out)+':'+cp,'org.junit.runner.JUnitCore','com.simon.voiceime.'+test_class]
result=subprocess.run(command)
raise SystemExit(result.returncode)
