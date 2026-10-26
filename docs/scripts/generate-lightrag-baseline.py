#!/usr/bin/env python3
"""Execute pinned, unmodified LightRAG functions using offline test doubles.

Development tool only. See docs/migration/STAGE_0.md and third_party/LightRAG/LICENSE.
"""
import argparse
import asyncio
import copy
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
from unittest.mock import patch

SHA = 'aaebfe1bb8d55deb6a4304d357a3bf4c92fcd40a'
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'backend/bootstart/src/test/resources/lightrag-baseline'
FILES = ['LICENSE', 'uv.lock', 'lightrag/base.py', 'lightrag/chunker/token_size.py',
         'lightrag/operate.py', 'lightrag/utils.py', 'lightrag/prompt.py',
         'lightrag/lightrag.py', 'lightrag/pipeline.py', 'lightrag/kg/pgtable_impl.py',
         'lightrag/kg/postgres_impl.py']


def serialized(value):
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + '\n'


async def generate(upstream):
    sys.path.insert(0, str(upstream))
    import lightrag.operate as op
    import lightrag.utils as util
    from lightrag.base import QueryParam
    from lightrag.chunker.token_size import chunking_by_token_size
    from lightrag.constants import GRAPH_FIELD_SEP
    from lightrag.prompt import PROMPTS

    class CodepointTokenizer:
        """Explicit deterministic test tokenizer, NOT a model BPE tokenizer."""
        def encode(self, content):
            return [ord(c) for c in content]
        def decode(self, tokens):
            return ''.join(chr(c) for c in tokens)

    tokenizer = util.Tokenizer('unicode-codepoint-test-v1', CodepointTokenizer())
    cases = []

    def add(case_id, family, source, inputs, expected):
        cases.append(dict(id=case_id, family=family, source=source, input=inputs, expected=expected))

    for name, text, size, overlap in [
        ('en', 'Alice uses LightRAG. Bob builds graphs.', 16, 4),
        ('zh', '张三使用知识图谱。李四使用向量检索。', 8, 2),
        ('emoji', '知识🙂 graph🚀', 5, 1), ('empty', '', 5, 0), ('invalid', 'abc', 3, 3)]:
        inp = dict(content=text, size=size, overlap=overlap)
        try:
            chunks = chunking_by_token_size(tokenizer, text, chunk_token_size=size,
                chunk_overlap_token_size=overlap, _emit_source_span=True)
            result = dict(tokens=tokenizer.encode(text), chunks=chunks)
        except ValueError:
            result = dict(error='ValueError')
        add('chunk-'+name, 'chunk', 'lightrag/chunker/token_size.py:chunking_by_token_size', inp, result)

    entities = [['entity', 'Alice', 'person', 'Alice uses LightRAG.'],
                ['entity', '张三', 'person', '张三使用知识图谱。'],
                ['entity', '', 'person', 'empty name'], ['entity', 'Alice']]
    relations = [['relation', 'Alice', 'LightRAG', 'uses,graph', 'Alice uses LightRAG.'],
                 ['relation', '张三', '知识图谱', '使用，检索', '张三使用知识图谱。'],
                 ['relation', 'Alice', 'Alice', 'self', 'self-loop'], ['relation', 'Alice']]
    for family, rows, function in [('entity', entities, op._handle_single_entity_extraction),
                                    ('relation', relations, op._handle_single_relationship_extraction)]:
        for i, row in enumerate(rows):
            inp = dict(record=row, chunk_id='c1', timestamp=1700000000, file_path='样本.md')
            add(f'{family}-{i}', family, f'lightrag/operate.py:{function.__name__}', inp,
                function(row, 'c1', 1700000000, '样本.md'))

    for i, response in enumerate([
        '{"high_level_keywords":["knowledge graph"],"low_level_keywords":["Alice","LightRAG"]}',
        '```json\n{"high_level_keywords":["知识图谱"],"low_level_keywords":["张三"]}\n```',
        {'high_level_keywords': '图谱; 检索', 'low_level_keywords': ['张三', None, 3, ' ']},
        '[]', None]):
        add(f'keywords-{i}', 'keywords', 'lightrag/operate.py:_parse_keywords_payload',
            dict(model_response=response), op._parse_keywords_payload(response))
    for i, (old, new) in enumerate([(['c1', 'c2'], ['c2', 'c3']),
                                   ([' c1'+GRAPH_FIELD_SEP+'c2 '], ['c1', 'c3']), (None, [])]):
        add(f'sources-{i}', 'sources', 'lightrag/utils.py:merge_source_ids',
            dict(existing=old, new=new), util.merge_source_ids(old, new))
    for i, (old, new) in enumerate([(['Alice uses RAG.', 'Alice uses RAG.'], ['New fact.', 'Alice uses RAG.']),
                                   (['张三使用图谱。'], ['张三使用图谱。', '知识融合。'])]):
        add(f'descriptions-{i}', 'descriptions', 'lightrag/operate.py:_combine_descriptions_dedup',
            dict(existing=old, new=new), op._combine_descriptions_dedup(old, new))
    for i, (maximum, groups) in enumerate([(1,1), (0,2), (4,3), (4,0)]):
        add(f'quota-{i}', 'quota', 'lightrag/operate.py:_vector_chunk_quota',
            dict(max=maximum, groups=groups), op._vector_chunk_quota(maximum, groups))

    chunk_rows = [dict(chunk_id='c1', content='Alice uses LightRAG.', file_path='english.md'),
                  dict(chunk_id='c2', content='张三使用知识图谱。', file_path='中文.md'),
                  dict(chunk_id='c3', content='向量检索支持知识融合。', file_path='中文.md'),
                  dict(chunk_id='c4', content='Unknown source', file_path='unknown_source')]
    for i, chunks in enumerate([chunk_rows, []]):
        references, updated = util.generate_reference_list_from_chunks(chunks)
        add(f'references-{i}', 'references', 'lightrag/utils.py:generate_reference_list_from_chunks',
            dict(chunks=chunks), dict(references=references, chunks=updated))
    references, updated = util.generate_reference_list_from_chunks(chunk_rows)
    rendered = util.render_chunks_context_text(updated)
    prompt = PROMPTS['rag_response'].format(context_data=rendered, response_type='Multiple Paragraphs', user_prompt='')
    add('context-bilingual', 'context', 'lightrag/utils.py:render_chunks_context_text + lightrag/prompt.py:rag_response',
        dict(chunks=updated, query='How does Alice use LightRAG? 张三使用什么？'),
        dict(context=rendered, prompt=prompt, references=references))
    for i, limit in enumerate([0, 5, 7, 99]):
        inp = dict(items=['知识', 'graph', '🙂'], separator='\n', limit=limit)
        value = util.truncate_list_by_token_size(inp['items'], key=lambda x:x, separator='\n',
                                                max_token_size=limit, tokenizer=tokenizer)
        add(f'budget-{i}', 'budget', 'lightrag/utils.py:truncate_list_by_token_size', inp, value)

    async def embed(texts, **kwargs):
        mapping = {'Alice':[1.,0.,0.], 'knowledge graph':[0.,1.,0.]}
        return [mapping.get(t, [1.,0.,0.]) for t in texts]

    nodes = {'Alice':dict(entity_type='person', description='Alice uses LightRAG.', source_id='c1', file_path='english.md'),
             'LightRAG':dict(entity_type='organization', description='A RAG framework.', source_id='c1'+GRAPH_FIELD_SEP+'c2', file_path='english.md'),
             '知识图谱':dict(entity_type='concept', description='知识融合。', source_id='c3', file_path='中文.md')}
    edges = {('Alice','LightRAG'):dict(weight=1.,description='Uses LightRAG.', keywords='uses',source_id='c1',file_path='english.md'),
             ('LightRAG','知识图谱'):dict(weight=2.,description='支持图谱。',keywords='graph',source_id='c3',file_path='中文.md')}

    class Graph:
        async def get_nodes_batch(self, ids): return {i:copy.deepcopy(nodes[i]) for i in ids if i in nodes}
        async def node_degrees_batch(self, ids): return {i:sum(i in e for e in edges) for i in ids}
        async def get_nodes_edges_batch(self, ids): return {i:[e for e in edges if i in e] for i in ids}
        async def get_edges_batch(self, pairs):
            return {(p['src'],p['tgt']):copy.deepcopy(edges.get(tuple(sorted((p['src'],p['tgt']))))) for p in pairs}
        async def edge_degrees_batch(self, pairs): return {tuple(p):sum(sum(i in e for e in edges) for i in p) for p in pairs}

    class Vectors:
        cosine_better_than_threshold = 0.1
        def __init__(self, rows): self.rows=rows; self.calls=[]
        async def query(self, query, top_k, query_embedding=None):
            self.calls.append(query)
            vector = query_embedding if query_embedding is not None else (await embed([query]))[0]
            scored=[]
            for row, embedding in self.rows:
                score = sum(a*b for a,b in zip(vector, embedding)) / math.sqrt(sum(a*a for a in vector)*sum(b*b for b in embedding))
                if score > self.cosine_better_than_threshold:
                    scored.append(dict(**row, distance=score))  # Upstream VDB contract calls its similarity field distance.
            return sorted(scored, key=lambda r:(-r['distance'],r['id']))[:top_k]

    def stores():
        entity = Vectors([(dict(id='e1',entity_name='Alice',created_at=1700000000), [1.,0.,0.]),
                          (dict(id='e2',entity_name='LightRAG',created_at=1700000000), [.8,.6,0.])])
        relation = Vectors([(dict(id='r1',src_id='LightRAG',tgt_id='知识图谱',created_at=1700000000), [0.,1.,0.]),
                            (dict(id='r2',src_id='Alice',tgt_id='LightRAG',created_at=1700000000), [0.,.8,.6])])
        chunks = Vectors([(dict(id=r['chunk_id'],content=r['content'],file_path=r['file_path']), v)
                          for r,v in zip(chunk_rows, [[1.,0.,0.],[.8,.6,0.],[0.,1.,0.],[0.,0.,1.]])])
        return entity, relation, chunks

    class Chunks:
        global_config = {'kg_chunk_pick_method':'WEIGHT', 'related_chunk_number':2}
        embedding_func = staticmethod(embed)
        async def get_by_ids(self, ids):
            by_id={r['chunk_id']:r for r in chunk_rows}
            return [copy.deepcopy(by_id.get(i)) for i in ids]

    for mode in ['local','global','hybrid','mix','naive']:
        entity, relation, chunks = stores()
        param = QueryParam(mode=mode, top_k=2, chunk_top_k=2, enable_rerank=False)
        if mode=='naive':
            result=await op._get_vector_context('question',chunks,param,[1.,0.,0.])
        else:
            result=await op._perform_kg_search('question','Alice','knowledge graph',Graph(),entity,relation,Chunks(),param,chunks)
        inp=dict(mode=mode,query='question',low_keywords='Alice',high_keywords='knowledge graph',top_k=2,
                 nodes=nodes,edges=[dict(src=a,tgt=b,**v) for (a,b),v in edges.items()],
                 entity_vectors=entity.rows,relation_vectors=relation.rows,chunk_vectors=chunks.rows,
                 embeddings=dict(query=[1.,0.,0.],low=[1.,0.,0.],high=[0.,1.,0.]))
        add('search-'+mode, 'search','lightrag/operate.py:_perform_kg_search / _get_vector_context',inp,
            dict(result=result,calls=dict(entities=entity.calls,relations=relation.calls,chunks=chunks.calls)))

    class FailingVectors:
        cosine_better_than_threshold = 0.1
        async def query(self, *args, **kwargs): raise RuntimeError('vector backend unavailable')
    try:
        await op._get_vector_context('question',FailingVectors(),QueryParam(),[1.,0.,0.])
        raise AssertionError('Backend error was swallowed')
    except RuntimeError as error:
        add('search-backend-error','search','lightrag/operate.py:_get_vector_context',dict(failure='backend'),dict(error=type(error).__name__))

    entity, relation, chunks = stores()
    filtered_nodes=[dict(entity_name=n,**d) for n,d in nodes.items()]
    filtered_edges=[dict(src_id=a,tgt_id=b,**d) for (a,b),d in edges.items()]
    direct=[chunk_rows[1],chunk_rows[0]]
    merged=await op._merge_all_chunks(filtered_nodes,filtered_edges,direct,query='question',
        knowledge_graph_inst=Graph(),text_chunks_db=Chunks(),query_param=QueryParam(enable_rerank=False),chunks_vdb=chunks)
    add('merge-chunks-three-sources','merge_chunks','lightrag/operate.py:_merge_all_chunks',
        dict(entities=filtered_nodes,relations=filtered_edges,vector_chunks=direct,selection='WEIGHT',related_chunk_number=2), merged)
    for i, descriptions in enumerate([['单条描述。'], ['First fact.', 'Second fact.']]):
        config=dict(tokenizer=tokenizer,summary_context_size=1000,summary_max_tokens=1000,force_llm_summary_on_merge=10)
        summary=await op._handle_entity_relation_summary('entity','Alice',descriptions,'\n',config)
        add(f'summary-{i}','summary','lightrag/operate.py:_handle_entity_relation_summary',
            dict(descriptions=descriptions,separator='\n',force_llm_summary_on_merge=10), summary)
    for i, args in enumerate([['mix','question'], ['local','question']]):
        add(f'cache-{i}','cache','lightrag/utils.py:compute_args_hash',dict(args=args),util.compute_args_hash(*args))

    summary_calls=[]
    async def summary_model(prompt, **kwargs):
        summary_calls.append(prompt)
        return 'Alice uses LightRAG and builds graphs.'
    config=dict(tokenizer=tokenizer,summary_context_size=10000,summary_max_tokens=10000,
                force_llm_summary_on_merge=2,summary_length_recommended=100,
                role_llm_funcs={'extract':summary_model},addon_params={})
    summary=await op._handle_entity_relation_summary('entity','Alice',['Alice uses LightRAG.','Alice builds graphs.'],'\n',config)
    add('summary-model-response','summary','lightrag/operate.py:_handle_entity_relation_summary / _summarize_descriptions',
        dict(descriptions=['Alice uses LightRAG.','Alice builds graphs.'],model_response='Alice uses LightRAG and builds graphs.',force_llm_summary_on_merge=2),
        dict(result=summary,model_prompts=summary_calls))
    async def rerank_model(**kwargs):
        return [dict(index=1,relevance_score=.9),dict(index=0,relevance_score=.2)]
    reranked=await util.apply_rerank_if_enabled('question',copy.deepcopy(chunk_rows[:2]),{'rerank_model_func':rerank_model},True,top_n=2)
    add('rerank-fixed-response','rerank','lightrag/utils.py:apply_rerank_if_enabled',
        dict(query='question',chunks=chunk_rows[:2],model_response=[dict(index=1,relevance_score=.9),dict(index=0,relevance_score=.2)]),reranked)

    # Drive the real merge round trip, freezing time only at this test boundary.
    class MemoryGraph:
        def __init__(self): self.nodes={}; self.edges={}
        async def get_node(self, name): return copy.deepcopy(self.nodes.get(name))
        async def upsert_node(self, name, node_data): self.nodes[name]=copy.deepcopy(node_data)
        async def has_node(self, name): return name in self.nodes
        async def has_edge(self, source, target): return tuple(sorted((source,target))) in self.edges
        async def get_edge(self, source, target): return copy.deepcopy(self.edges.get(tuple(sorted((source,target)))))
        async def upsert_edge(self, source, target, edge_data): self.edges[tuple(sorted((source,target)))]=copy.deepcopy(edge_data)

    class MemoryVectors:
        def __init__(self): self.data={}
        async def upsert(self, data): self.data.update(copy.deepcopy(data))
        async def delete(self, ids):
            for key in ids: self.data.pop(key,None)

    config=dict(tokenizer=tokenizer,summary_context_size=1000000,summary_max_tokens=1000000,
                force_llm_summary_on_merge=10, source_ids_limit_method='KEEP',
                max_source_ids_per_entity=1000,max_source_ids_per_relation=1000,max_file_paths=100)
    graph=MemoryGraph(); vectors=MemoryVectors()
    records=[dict(entity_type='person',description='Alice uses LightRAG.',source_id='c1',file_path='english.md',timestamp=1700000000),
             dict(entity_type='person',description='Alice uses LightRAG.',source_id='c1',file_path='english.md',timestamp=1700000000),
             dict(entity_type='person',description='Alice builds graphs.',source_id='c2',file_path='中文.md',timestamp=1700000001)]
    snapshots=[]
    with patch('lightrag.operate.time.time',return_value=1700000000):
        for record in records:
            await op._merge_nodes_then_upsert('Alice',[record],graph,vectors,config)
            snapshots.append(copy.deepcopy(graph.nodes['Alice']))
    add('node-merge-reprocess','node_merge','lightrag/operate.py:_merge_nodes_then_upsert',
        dict(entity_name='Alice',records=records,time=1700000000),dict(snapshots=snapshots,vectors=vectors.data))

    graph.nodes['LightRAG']=dict(entity_id='LightRAG',entity_type='concept',description='RAG framework',source_id='c1',file_path='english.md')
    relation_records=[dict(source_id=c,weight=1.,description='Alice uses LightRAG.',keywords='uses',file_path='english.md') for c in ['c1','c1','c2']]
    snapshots=[]; relation_vectors=MemoryVectors()
    with patch('lightrag.operate.time.time',return_value=1700000000):
        for record in relation_records:
            await op._merge_edges_then_upsert('Alice','LightRAG',[record],graph,relation_vectors,vectors,config)
            snapshots.append(copy.deepcopy(graph.edges[('Alice','LightRAG')]))
    add('edge-merge-reprocess','edge_merge','lightrag/operate.py:_merge_edges_then_upsert',
        dict(source='Alice',target='LightRAG',records=relation_records,time=1700000000),dict(snapshots=snapshots,vectors=relation_vectors.data))

    responses=[
        'entity<|#|>Alice<|#|>person<|#|>Alice uses LightRAG.\nentity<|#|>LightRAG<|#|>concept<|#|>RAG framework.\nrelation<|#|>Alice<|#|>LightRAG<|#|>uses<|#|>Alice uses LightRAG.\n<|COMPLETE|>',
        'entity<|#|>张三<|#|>person<|#|>张三使用知识图谱。\n<|COMPLETE|>'
    ]
    calls=[]
    async def llm(prompt, **kwargs):
        calls.append(dict(prompt=prompt,system_prompt=kwargs.get('system_prompt'),history_messages=kwargs.get('history_messages')))
        return responses[len(calls)-1]
    cfg=dict(llm_model_func=llm,role_llm_funcs={role:llm for role in ['extract','keyword','query','vlm']},
             entity_extract_max_gleaning=1,entity_extract_max_records=100,entity_extract_max_entities=40,
             addon_params={},tokenizer=tokenizer,llm_model_max_async=1)
    input_chunks={'c1':dict(content='Alice uses LightRAG. 张三使用知识图谱。',tokens=40,full_doc_id='doc1',chunk_order_index=0,file_path='sample.md')}
    with patch('lightrag.operate.time.time',return_value=1700000000), patch.dict('os.environ',{'MAX_EXTRACT_INPUT_TOKENS':'999999'}):
        extracted=await op.extract_entities(copy.deepcopy(input_chunks),cfg)
    normalized=[]
    for extracted_nodes, extracted_edges in extracted:
        normalized.append(dict(nodes=extracted_nodes,edges=[dict(pair=list(pair),records=rows) for pair,rows in sorted(extracted_edges.items())]))
    add('extraction-with-gleaning','extraction_pipeline','lightrag/operate.py:extract_entities',
        dict(chunks=input_chunks,model_responses=responses,max_gleaning=1,max_input_tokens=999999,time=1700000000),
        dict(results=normalized,model_calls=calls))

    return dict(schema_version=1,upstream_commit=SHA,tokenizer='unicode-codepoint-test-v1',
        model_responses='hand-authored; no real model calls',
        vector_backend='test-only exact cosine, fixed vectors, stable ID tie-break',cases=cases)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream',type=Path,required=True)
    parser.add_argument('--check',action='store_true',help='Compare only; never overwrite reviewed fixtures')
    args=parser.parse_args()
    upstream=args.upstream.resolve()
    actual=subprocess.check_output(['git','-C',str(upstream),'rev-parse','HEAD'],text=True).strip()
    if actual!=SHA: raise SystemExit('Wrong upstream commit: '+actual)
    subprocess.run(['git','-C',str(upstream),'diff','--exit-code','HEAD','--',*FILES],check=True,stdout=subprocess.DEVNULL)
    corpus=asyncio.run(generate(upstream))
    artifacts={'corpus.json':serialized(corpus)}
    artifacts['provenance.json']=serialized(dict(upstream_commit=SHA,
        source_sha256={p:hashlib.sha256((upstream/p).read_bytes()).hexdigest() for p in FILES},
        corpus_sha256=hashlib.sha256(artifacts['corpus.json'].encode()).hexdigest(),case_count=len(corpus['cases'])))
    OUT.mkdir(parents=True,exist_ok=True)
    for name,content in artifacts.items():
        destination=OUT/name
        if args.check:
            if not destination.exists() or destination.read_text()!=content: raise SystemExit('Baseline drift: '+name)
        else: destination.write_text(content)
    print(f'{"Verified" if args.check else "Generated"} {len(corpus["cases"])} cases at upstream {SHA}')

if __name__=='__main__': main()
