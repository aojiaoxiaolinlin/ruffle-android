package rs.ruffle

/** Official QR-assisted login and read-only extraction of game parameters. */
internal object RocoLoginPage {
    // Inject only into Tencent's login frame, including cross-origin iframes.
    // No credentials or native JS bridge are exposed. Use Tencent's own QR poller.
    val useQqPolling = """
        (function(){
            if(location.protocol!=='https:' ||
                location.hostname!=='xui.ptlogin2.qq.com' ||
                location.pathname!=='/cgi-bin/xlogin')return;
            var lastStart=0;
            function start(e){
                var n=e.target;
                while(n && n.id!=='onekey')n=n.parentElement;
                if(!n || typeof pt!=='object' || !pt.qrcode ||
                    typeof pt.qrcode.get!=='function')return;
                e.preventDefault();e.stopImmediatePropagation();
                if(Date.now()-lastStart<1200)return;
                lastStart=Date.now();
                pt.qrcode.get(1);
            }
            document.addEventListener('touchstart',start,{capture:true,passive:false});
            document.addEventListener('click',start,true);
        })()
    """.trimIndent()

    // Poll after resume as well as page-load events: OAuth can finish inside an iframe.
    val readResult = """
        (function(){
            function trusted(w){
                try {
                    var l=w.location;
                    return l.protocol==='https:' &&
                        ((l.hostname==='17roco.qq.com' &&
                          (l.pathname==='/main.html' || l.pathname==='/logintarget.html')) ||
                         (l.hostname==='web2.17roco.qq.com' &&
                          (l.pathname==='/fcgi-bin/login3' || l.pathname==='/fcgi-bin/login2')));
                } catch(e){return false;}
            }
            function visit(w,depth){
                if(depth>8)return null;
                if(trusted(w)){
                    try {
                        var u=new URL(w.location.href);
                        var code=u.searchParams.get('code');
                        if(code && u.pathname==='/logintarget.html')return {code:code};
                        var doc=w.document;
                        var nodes=doc.querySelectorAll('param,embed');
                        for(var i=0;i<nodes.length;i++){
                            var n=nodes[i],v=n.tagName.toLowerCase()==='param'
                                ? ((n.getAttribute('name')||'').toLowerCase()==='flashvars'
                                    ? n.getAttribute('value'):null)
                                : n.getAttribute('flashvars');
                            if(v && v.indexOf('angel_key=')>=0)return {vars:v};
                        }
                        if(u.href.indexOf('night.swf')>=0)return {night:true};
                        if(doc.documentElement &&
                            doc.documentElement.innerHTML.indexOf('night.swf')>=0)return {night:true};
                    } catch(e){}
                }
                try {
                    for(var j=0;j<w.frames.length;j++){
                        var result=visit(w.frames[j],depth+1);
                        if(result)return result;
                    }
                }catch(e){}
                return null;
            }
            return visit(window,0);
        })()
    """.trimIndent()
}
