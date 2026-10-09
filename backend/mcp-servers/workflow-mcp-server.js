import http from 'node:http';

// This is an independent enterprise-system fixture, not a production ERP.
// stdout is reserved exclusively for JSON-RPC; diagnostics go to stderr.
const reports = {
  '2026-09': { revenue: 1286000, orders: 842, refunds: 26, previousRevenue: 1140000 },
  '2026-08': { revenue: 1140000, orders: 790, refunds: 31, previousRevenue: 1080000 },
  '2026-07': { revenue: 1080000, orders: 746, refunds: 22, previousRevenue: 1010000 }
};

const orders = {
  'SO20261001': { orderId: 'SO20261001', product: 'Aero 耳机', amount: 599, status: '已签收', deliveredDays: 3, used: false, shipping: 12 },
  'SO20261002': { orderId: 'SO20261002', product: 'Home 智能音箱', amount: 899, status: '运输中', deliveredDays: 0, used: false, shipping: 0 },
  'SO20261003': { orderId: 'SO20261003', product: 'Aero 耳机', amount: 599, status: '已签收', deliveredDays: 15, used: true, shipping: 12 }
};
const httpServer = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  const send = (code, body) => { res.writeHead(code); res.end(JSON.stringify(body)); };
  if (req.method !== 'GET') return send(405, { error: 'Only read-only GET is supported' });
  if (url.pathname === '/health') return send(200, { status: 'UP', source: 'external-enterprise-fixture' });
  const id = url.searchParams.get('orderId');
  const order = orders[id];
  if (!order) return send(404, { error: '订单不存在', orderId: id });
  if (url.pathname === '/orders') return send(200, { source: 'external-order-fixture', ...order });
  if (url.pathname === '/refund/quote') {
    const eligible = order.status === '已签收' && order.deliveredDays <= 7 && !order.used;
    return send(200, { source: 'external-refund-fixture', orderId: id, eligible,
      refundableAmount: eligible ? order.amount : 0, shipping: order.shipping,
      policy: '7天内未使用商品可退货 商品金额全额退还 运费按责任认定', action: '只读试算 未执行退款' });
  }
  send(404, { error: 'Unknown endpoint' });
});
httpServer.listen(Number(process.env.TOOL_PORT || 18083), '127.0.0.1', () => console.error('Enterprise HTTP fixture ready'));
httpServer.on('error', err => { console.error(err.message); process.exit(1); });

process.stdin.on('end', () => httpServer.close(() => process.exit(0)));

// Dependency-free JSON-RPC adapter, matching the existing cockpit stdio servers.
// Business fixtures and real HTTP calls are unchanged. stdout is protocol-only.
import readline from 'node:readline';
function send(id,result,error){
 const packet=JSON.stringify({jsonrpc:'2.0',id,...(error?{error}:{result})})
   .replace(/[\u007f-\uffff]/g,c=>'\\u'+c.charCodeAt(0).toString(16).padStart(4,'0'));
 process.stdout.write(packet+'\n');
}
const input=readline.createInterface({input:process.stdin,crlfDelay:Infinity});
input.on('line',line=>{
 let r;try{r=JSON.parse(line)}catch{send(null,null,{code:-32700,message:'Parse error'});return;}
 if(r.id===undefined)return;
 try{
  if(r.method==='initialize')return send(r.id,{protocolVersion:r.params?.protocolVersion||'2024-11-05',capabilities:{tools:{}},serverInfo:{name:'cockpit-workflow-fixture',version:'1.0.0'}});
  if(r.method==='ping')return send(r.id,{});
  if(r.method==='tools/list')return send(r.id,{tools:[{name:'query_sales_report',description:'只读企业演示报表，金额单位人民币元，支持2026-07/08/09',inputSchema:{type:'object',properties:{period:{type:'string',pattern:'^2026-0[789]$'}},required:['period'],additionalProperties:false}}]});
  if(r.method==='tools/call'){
   const a=r.params?.arguments;
   if(r.params?.name!=='query_sales_report'||!a||Object.keys(a).length!==1||!/^2026-0[789]$/.test(a.period))
    return send(r.id,{isError:true,content:[{type:'text',text:'无效工具或报表月份'}]});
   const row=reports[a.period];
   return send(r.id,{content:[{type:'text',text:JSON.stringify({source:'enterprise-fixture',period:a.period,currency:'CNY',...row,growthPercent:Number(((row.revenue/row.previousRevenue-1)*100).toFixed(2))})}]});
  }
  send(r.id,null,{code:-32601,message:'Method not found'});
 }catch{send(r.id,null,{code:-32603,message:'Internal error'});}
});
