#!/usr/bin/env python3
"""Run pinned LightRAG with the genuine cl100k vocabulary verified against tiktoken's official hash."""
import argparse,hashlib,json,os,subprocess,sys,zipfile,tempfile
from pathlib import Path
SHA='aaebfe1bb8d55deb6a4304d357a3bf4c92fcd40a'
VOCAB_SHA='223921b76ee99bde995b7ff738513eef100fb51d18c93597a113bcffe865b2a7'
p=argparse.ArgumentParser();p.add_argument('--upstream',type=Path,required=True);p.add_argument('--vocab-jar',type=Path,required=True);p.add_argument('--check',action='store_true');args=p.parse_args()
assert subprocess.check_output(['git','-C',str(args.upstream),'rev-parse','HEAD'],text=True).strip()==SHA
subprocess.run(['git','-C',str(args.upstream),'diff','--exit-code','HEAD','--','lightrag/chunker/token_size.py','lightrag/utils.py'],check=True)
with zipfile.ZipFile(args.vocab_jar) as archive:vocabulary=archive.read('com/knuddels/jtokkit/cl100k_base.tiktoken')
assert hashlib.sha256(vocabulary).hexdigest()==VOCAB_SHA
cache=Path(tempfile.gettempdir())/'tjlightrag-tiktoken-cache';cache.mkdir(parents=True,exist_ok=True)
url='https://openaipublic.blob.core.windows.net/encodings/cl100k_base.tiktoken'
(cache/hashlib.sha1(url.encode()).hexdigest()).write_bytes(vocabulary)
os.environ['TIKTOKEN_CACHE_DIR']=str(cache)
sys.path.insert(0,str(args.upstream))
import tiktoken
from lightrag.utils import Tokenizer
from lightrag.chunker.token_size import chunking_by_token_size
from lightrag.exceptions import ChunkTokenLimitExceededError
encoding=tiktoken.get_encoding('cl100k_base')
class Adapter:
 def encode(self,text):return encoding.encode(text,disallowed_special=())
 def decode(self,tokens):return encoding.decode(tokens)
tokenizer=Tokenizer('cl100k_base',Adapter());cases=[]
for name,text,size,overlap in [('en','Alice uses LightRAG. Bob builds graphs.',8,2),('zh','张三使用知识图谱。李四使用向量检索。',8,2),('emoji','知识🙂 graph🚀 and repeated 中文🙂 words.',5,1),('whitespace','  标题\r\n\r\nParagraph text.  ',8,2),('literal-special','Literal <|endoftext|> remains text.',8,2),('empty','',8,2)]:
 cases.append(dict(id=name,input=dict(content=text,size=size,overlap=overlap),expected=dict(tokens=tokenizer.encode(text),chunks=chunking_by_token_size(tokenizer,text,chunk_token_size=size,chunk_overlap_token_size=overlap,_emit_source_span=True))))
for name,text,size,overlap,only in [('delimiter','第一段🙂\nSecond paragraph with repeated graph words.\n',8,2,False),('delimiter-only','first\n第二段\n',16,2,True),('delimiter-overflow','knowledge graph text '*20,8,2,True)]:
 inputs=dict(content=text,size=size,overlap=overlap,delimiter='\n',only=only)
 expected=dict(tokens=tokenizer.encode(text))
 try:expected['chunks']=chunking_by_token_size(tokenizer,text,split_by_character='\n',split_by_character_only=only,chunk_token_size=size,chunk_overlap_token_size=overlap,_emit_source_span=True)
 except ChunkTokenLimitExceededError as error:expected['error']=type(error).__name__
 cases.append(dict(id=name,input=inputs,expected=expected))
result=dict(upstream_commit=SHA,tokenizer='cl100k_base',vocabulary_sha256=VOCAB_SHA,literal_special_tokens=True,cases=cases)
text=json.dumps(result,ensure_ascii=False,indent=2,sort_keys=True)+'\n'
out=Path(__file__).resolve().parents[2]/'backend/bootstart/src/test/resources/lightrag-baseline/bpe-corpus.json'
if args.check:assert out.read_text()==text,'BPE reference drift'
else:out.write_text(text)
print(('Verified' if args.check else 'Generated')+f' {len(cases)} real BPE cases; official vocabulary hash verified')
