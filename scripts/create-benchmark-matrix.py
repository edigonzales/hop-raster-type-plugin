#!/usr/bin/env python3
"""Generate equivalent full Hop pipelines for the frozen file API and Raster V1."""
import argparse,json
from pathlib import Path
import xml.etree.ElementTree as E

p=argparse.ArgumentParser();p.add_argument('--baseline-hop',required=True,type=Path);p.add_argument('--candidate-hop',required=True,type=Path);p.add_argument('--fixtures',required=True,type=Path);p.add_argument('--output',required=True,type=Path);p.add_argument('--java-home',required=True);p.add_argument('--raster-zip',type=Path,required=True);p.add_argument('--vector-zip',type=Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
config=a.output/'config';(config/'metadata/pipeline-run-configuration').mkdir(parents=True,exist_ok=True)
(config/'metadata/pipeline-run-configuration/local.json').write_text(json.dumps({'name':'local','engineRunConfiguration':{'Local':{'rowset_size':'2','safe_mode':True}},'configurationVariables':[]}))
manifest={'reference_commit':'4d2a81c7305c7618eb49359739cca8500a00e0aa','http_root':str(a.fixtures.resolve()),'artifacts':{'raster':str(a.raster_zip.resolve()),'vector':str(a.vector_zip.resolve())},'env':{'JAVA_HOME':a.java_home,'HOP_CONFIG_FOLDER':str(config.resolve()),'HOP_OPTIONS':'-Xms128m -Xmx512m'},'cases':[]}

def add(parent,name,text):E.SubElement(parent,name).text=str(text)
def pipeline(path,source,side,mode,small=False,nearest=False):
    root=E.Element('pipeline');info=E.SubElement(root,'info');add(info,'name',path.stem);add(info,'pipeline_type','Normal');order=E.SubElement(root,'order');nodes=[]
    def node(name,kind,**values):
        t=E.SubElement(root,'transform');add(t,'name',name);add(t,'type',kind);add(t,'copies',1);add(t,'distribute','Y')
        for k,v in values.items():add(t,k,v)
        if nodes:h=E.SubElement(order,'hop');add(h,'from',nodes[-1]);add(h,'to',name);add(h,'enabled','Y')
        nodes.append(name);return t
    gen=node('rows','RowGenerator',limit=50 if mode=='stats' else 1,never_ending='N');fields=E.SubElement(gen,'fields');f=E.SubElement(fields,'field');add(f,'name','id');add(f,'type','Integer');add(f,'nullif','1')
    if mode=='stats':
        f=E.SubElement(fields,'field');add(f,'name','geometry');add(f,'type','Geometry');add(f,'nullif','POLYGON ((2600000 1200000,2600050 1200000,2600050 1200050,2600000 1200050,2600000 1200000))')
    if side=='candidate':node('reader','SOGIS_RASTER_READER',version=1,source=source,rasterField='raster')
    outputs=[];clipfile=path.with_suffix('.clip.tif');outfile=path.with_suffix('.out.tif')
    if mode in ('clip','chain'):
        values={'clipMethod':'BOUNDING_BOX','explicitCrs':'EPSG:2056','minX':2600000,'minY':1200000,'maxX':2600050 if small else 2600900,'maxY':1200050 if small else 1200900,'noData':'-9999','prefix':'clip_'}
        if side=='baseline':node('clip','SOGIS_RASTER_CLIP',source=source,band=1,output=clipfile if mode=='chain' else outfile,overwrite='Y',**values);outputs.append(str(clipfile if mode=='chain' else outfile))
        else:node('clip','SOGIS_RASTER_VALUE_CLIP',version=1,rasterField='raster',bands='1',**values)
    if mode in ('reproject','chain'):
        values={'targetCrs':'EPSG:2056','resolutionX':1,'resolutionY':1,'extentMode':'AUTO','interpolation':'NEAREST' if nearest else 'BILINEAR','outputType':'AUTO','prefix':'warp_'}
        if side=='baseline':node('warp','SOGIS_RASTER_REPROJECT',source=clipfile if mode=='chain' else source,output=outfile,overwrite='Y',**values);outputs.append(str(outfile))
        else:node('warp','SOGIS_RASTER_VALUE_REPROJECT',version=1,rasterField='raster',**values)
    if mode=='stats':
        values={'geometryField':'geometry','explicitCrs':'EPSG:2056','band':1,'prefix':'stats_','statistics':'mean min max sum stddev count'}
        if side=='baseline':node('statistics','SOGIS_RASTER_ZONAL_STATS',source=source,**values)
        else:node('statistics','SOGIS_RASTER_VALUE_ZONAL_STATS',version=1,rasterField='raster',**values)
    elif side=='candidate':node('writer','SOGIS_RASTER_WRITER',version=1,rasterField='raster',output=outfile,overwrite='Y',prefix='out_');outputs.append(str(outfile))
    if mode=='stats':
        outfile=path.with_suffix('.stats.csv')
        sink=node('results','TextFileOutput',separator=';',enclosure='"',header='Y',footer='N',format='UNIX',encoding='UTF-8',append='N')
        file=E.SubElement(sink,'file');add(file,'name',outfile);add(file,'extention','');add(file,'split','N');add(file,'add_stepnr','N');add(file,'add_date','N');add(file,'add_time','N');add(file,'pad','N');add(file,'fast_dump','N')
        fields=E.SubElement(sink,'fields')
        for stat in ('mean','min','max','sum','stddev','count','status'):
            f=E.SubElement(fields,'field');add(f,'name','stats_'+stat);add(f,'type','String' if stat=='status' else 'Number');add(f,'format','0.#################');add(f,'decimal','.');add(f,'null','NULL');add(f,'trim_type','none')
        outputs.append(str(outfile))
    E.SubElement(root,'transform_error_handling');E.SubElement(root,'attributes');E.indent(root,space='  ');path.write_text(E.tostring(root,encoding='unicode'));return outputs
for name,kind,mode,remote,small,nearest in [
 ('dem-small-clip','dem','clip',False,True,False),('dem-large-clip','dem','clip',False,False,False),('dem-nearest','dem','reproject',False,False,True),('dem-bilinear','dem','reproject',False,False,False),('dem-chain','dem','chain',False,False,False),('dem-http-chain','dem','chain',True,False,False),('rgb-bilinear','rgb','reproject',False,False,False),('rgb-http-bilinear','rgb','reproject',True,False,False),('dem-zones','dem','stats',False,False,False),('dem-http-zones','dem','stats',True,False,False)]:
    case={'name':name};source=f'http://127.0.0.1:18556/{kind}.tif' if remote else str((a.fixtures/(kind+'.tif')).resolve())
    for side,home in [('baseline',a.baseline_hop),('candidate',a.candidate_hop)]:
        path=(a.output/(name+'-'+side+'.hpl')).resolve();outputs=pipeline(path,source,side,mode,small,nearest);case[side]={'cwd':str(home.resolve()),'command':[str((home/'hop-run.sh').resolve()),'-r','local','-f',str(path)],'outputs':outputs}
    manifest['cases'].append(case)
# Additional independent scenarios retain the original baseline algorithms.
import copy
for name,template in [('crs-change','dem-bilinear'),('strong-resampling','dem-bilinear'),('polygon-holes','dem-large-clip'),('long-chain','dem-chain'),('fan-out','dem-chain'),('repeated-clips','dem-small-clip'),('changing-sources','dem-zones')]:
    case=copy.deepcopy(next(c for c in manifest['cases'] if c['name']==template));case['name']=name
    if name in ('crs-change','strong-resampling'):case['comparison_relative_tolerance']=1e-6
    for side in ('baseline','candidate'):
        spec=case[side];old=Path(spec['command'][-1]);path=old.with_name(name+'-'+side+'.hpl');root=E.fromstring(old.read_text().replace(template+'-'+side,name+'-'+side))
        spec['command'][-1]=str(path);spec['outputs']=[f.replace(template+'-'+side,name+'-'+side) for f in spec['outputs']]
        warp=next((t for t in root.findall('transform') if t.findtext('name')=='warp'),None)
        if name=='crs-change':
            warp.find('targetCrs').text='EPSG:3857'
        if name=='strong-resampling':
            warp.find('resolutionX').text=warp.find('resolutionY').text='8'
        if name=='polygon-holes':
            clip=next(t for t in root.findall('transform') if t.findtext('name')=='clip');clip.find('clipMethod').text='POLYGON';add(clip,'geometryField','geometry')
            fields=root.find('transform/fields');f=E.SubElement(fields,'field');add(f,'name','geometry');add(f,'type','Geometry');add(f,'nullif','POLYGON ((2600000 1200000,2600900 1200000,2600900 1200900,2600000 1200900,2600000 1200000),(2600200 1200200,2600200 1200400,2600400 1200400,2600400 1200200,2600200 1200200))')
        if name=='long-chain':
            order=root.find('order');last='warp'
            for i in range(1,4):
                step=copy.deepcopy(warp);step.find('name').text='warp'+str(i);step.find('prefix').text='warp'+str(i)+'_'
                if side=='baseline':
                    step.find('source').text=str(path.with_suffix('.out.tif')) if i==1 else str(path.with_suffix('.stage'+str(i-1)+'.tif'))
                    step.find('output').text=str(path.with_suffix('.stage'+str(i)+'.tif'));spec['outputs'].append(step.findtext('output'))
                else:
                    for hop in list(order):
                        if hop.findtext('from')==last and hop.findtext('to')=='writer':order.remove(hop)
                root.append(step);h=E.SubElement(order,'hop');add(h,'from',last);add(h,'to','warp'+str(i));add(h,'enabled','Y');last='warp'+str(i)
            if side=='candidate':
                h=E.SubElement(order,'hop');add(h,'from',last);add(h,'to','writer');add(h,'enabled','Y')
        if name=='fan-out':
            terminal=next(t for t in root.findall('transform') if t.findtext('name')==('warp' if side=='baseline' else 'writer'))
            branch=copy.deepcopy(terminal);branch.find('name').text='branch';branch.find('prefix').text='branch_';branch.find('output').text=str(path.with_suffix('.branch.tif'));root.append(branch)
            parent=next(t for t in root.findall('transform') if t.findtext('name')==('clip' if side=='baseline' else 'warp'));parent.find('distribute').text='N'
            h=E.SubElement(root.find('order'),'hop');add(h,'from',parent.findtext('name'));add(h,'to','branch');add(h,'enabled','Y');spec['outputs'].append(branch.findtext('output'))
        if name in ('repeated-clips','changing-sources'):
            gen=root.find('transform');gen.find('type').text='DataGrid'
            for child in list(gen):
                if child.tag not in ('name','type','copies','distribute'):gen.remove(child)
            fields=E.SubElement(gen,'fields');data=E.SubElement(gen,'data')
            if name=='repeated-clips':
                names=[('xmin','Number'),('ymin','Number'),('xmax','Number'),('ymax','Number')]
                clip=next(t for t in root.findall('transform') if t.findtext('name')=='clip');add(clip,'bboxFields','Y')
                for key,val in zip(('minX','minY','maxX','maxY'),('xmin','ymin','xmax','ymax')):clip.find(key).text=val
                rows=[[2600000+(i%100),1200000+(i//100),2600010+(i%100),1200010+(i//100)] for i in range(1000)]
            else:
                names=[('source','String'),('geometry','Geometry')]
                geom='POLYGON ((2600000 1200000,2600050 1200000,2600050 1200050,2600000 1200050,2600000 1200000))'
                rows=[[str((a.fixtures/('dem.tif' if i%2==0 else 'rgb.tif')).resolve()),geom] for i in range(100)]
                target=next(t for t in root.findall('transform') if t.findtext('name')==('statistics' if side=='baseline' else 'reader'));target.find('source').text='source';add(target,'sourceField','Y')
            for n,typ in names:
                f=E.SubElement(fields,'field');add(f,'name',n);add(f,'type',typ)
            for values in rows:
                r=E.SubElement(data,'line')
                for value in values:add(r,'item',value)
        E.indent(root,space='  ');path.write_text(E.tostring(root,encoding='unicode'))
    manifest['cases'].append(case)
(a.output/'matrix.json').write_text(json.dumps(manifest,indent=2))
