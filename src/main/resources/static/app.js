/* 管理后台前端（免构建版）：Vue3 + Element Plus + ECharts，通过 axios 轮询后端只读接口。 */
const { createApp, ref, onMounted, onBeforeUnmount, nextTick, watch } = Vue;

const api = axios.create({ baseURL: '' });

const pct = (v) => (v == null ? '-' : (Number(v) * 100).toFixed(2) + '%');
const money = (v) => (v == null ? '-' : Number(v).toFixed(2));
const num = (v, d) => (v == null ? '-' : Number(v).toFixed(d || 4));

const app = createApp({
  setup() {
    const active = ref('overview');
    const loading = ref(false);

    const overview = ref(null);
    const positions = ref([]);
    const candidates = ref([]);
    const orders = ref([]);
    const fills = ref([]);
    const fundingIncome = ref([]);
    const events = ref([]);
    const params = ref([]);
    const curve = ref([]);
    const pnl = ref(null);
    const host = ref(null);
    const hostHistory = ref([]);
    const tradeTab = ref('fills');

    // 候选池单币费率曲线弹窗
    const frDialog = ref(false);
    const frSymbol = ref('');
    const frHistory = ref([]);

    let equityChart = null;
    let frChart = null;
    let hostCpuChart = null;
    let hostMemChart = null;

    async function loadLight() {
      try {
        const [ov, pos, ev, cv, pl, hs] = await Promise.all([
          api.get('/api/admin/overview'),
          api.get('/api/admin/positions'),
          api.get('/api/admin/events?limit=100'),
          api.get('/api/admin/equity-curve?limit=1000'),
          api.get('/api/admin/pnl'),
          api.get('/api/admin/host'),
        ]);
        overview.value = ov.data;
        positions.value = pos.data;
        events.value = ev.data;
        curve.value = cv.data;
        pnl.value = pl.data;
        host.value = hs.data;
        renderEquity();
      } catch (e) {
        console.error('刷新失败', e);
      }
    }

    async function loadHeavy() {
      try {
        const [cd, od, fl, fi, pr, hh] = await Promise.all([
          api.get('/api/admin/candidates'),
          api.get('/api/admin/orders?limit=100'),
          api.get('/api/admin/fills?limit=100'),
          api.get('/api/admin/funding-income?limit=100'),
          api.get('/api/admin/params'),
          api.get('/api/admin/host-history?limit=1000'),
        ]);
        candidates.value = cd.data;
        orders.value = od.data;
        fills.value = fl.data;
        fundingIncome.value = fi.data;
        params.value = pr.data;
        hostHistory.value = hh.data;
        renderHost();
      } catch (e) {
        console.error('刷新失败', e);
      }
    }

    function renderEquity() {
      nextTick(() => {
        const el = document.getElementById('equity-chart');
        if (!el) return;
        // 说明：图表容器写在 v-if 里，切到别的菜单时它会被销毁、切回来会新建一个节点。
        // 旧的 ECharts 实例仍指向已被移除的 DOM，直接 setOption 会画不出来（页面空白），
        // 所以容器一变就销毁重建。
        if (equityChart && equityChart.getDom() !== el) {
          equityChart.dispose();
          equityChart = null;
        }
        if (!equityChart) equityChart = echarts.init(el);
        if (!curve.value || !curve.value.length) return;
        const rows = curve.value.slice().reverse(); // 时间升序
        equityChart.setOption({
          tooltip: { trigger: 'axis' },
          grid: { left: 60, right: 20, top: 20, bottom: 40 },
          xAxis: { type: 'category', data: rows.map(r => (r.sourceTime || '').replace('T', ' ').slice(5, 16)) },
          yAxis: { type: 'value', scale: true },
          series: [{
            name: '总权益', type: 'line', smooth: true, showSymbol: false,
            data: rows.map(r => Number(r.accountEquityUsd)),
            areaStyle: { opacity: 0.08 },
          }],
        });
      });
    }

    // 字节 → 人类可读：小于 1GB 显示 MB，否则显示 GB
    function gb(bytes) {
      if (bytes == null) return '-';
      const b = Number(bytes);
      if (b < 1024 * 1024 * 1024) return (b / 1024 / 1024).toFixed(0) + ' MB';
      return (b / 1024 / 1024 / 1024).toFixed(1) + ' GB';
    }
    function ratioPct(used, total) {
      if (used == null || total == null || Number(total) <= 0) return '-';
      return (Number(used) / Number(total) * 100).toFixed(1) + '%';
    }
    function memPct() { return host.value ? ratioPct(host.value.memUsedBytes, host.value.memTotalBytes) : '-'; }
    function diskPct() { return host.value ? ratioPct(host.value.diskUsedBytes, host.value.diskTotalBytes) : '-'; }

    function renderHost() {
      nextTick(() => {
        const rows = (hostHistory.value || []).slice().reverse(); // 时间升序
        const x = rows.map(r => (r.sampledAt || '').replace('T', ' ').slice(5, 16));

        const cpuEl = document.getElementById('host-cpu-chart');
        if (cpuEl) {
          // 和收益曲线同理：容器在 v-if 里会被销毁重建，实例节点变了要重新初始化
          if (hostCpuChart && hostCpuChart.getDom() !== cpuEl) { hostCpuChart.dispose(); hostCpuChart = null; }
          if (!hostCpuChart) hostCpuChart = echarts.init(cpuEl);
          hostCpuChart.setOption({
            tooltip: { trigger: 'axis' },
            legend: { data: ['整机 CPU', '本进程 CPU'] },
            grid: { left: 50, right: 20, top: 40, bottom: 40 },
            xAxis: { type: 'category', data: x },
            yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
            series: [
              { name: '整机 CPU', type: 'line', smooth: true, showSymbol: false, data: rows.map(r => r.cpuLoadPct) },
              { name: '本进程 CPU', type: 'line', smooth: true, showSymbol: false, data: rows.map(r => r.processCpuLoadPct) },
            ],
          });
        }

        const memEl = document.getElementById('host-mem-chart');
        if (memEl) {
          if (hostMemChart && hostMemChart.getDom() !== memEl) { hostMemChart.dispose(); hostMemChart = null; }
          if (!hostMemChart) hostMemChart = echarts.init(memEl);
          hostMemChart.setOption({
            tooltip: { trigger: 'axis' },
            legend: { data: ['内存使用率', '磁盘使用率'] },
            grid: { left: 50, right: 20, top: 40, bottom: 40 },
            xAxis: { type: 'category', data: x },
            yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
            series: [
              { name: '内存使用率', type: 'line', smooth: true, showSymbol: false,
                data: rows.map(r => r.memTotalBytes ? Number((r.memUsedBytes / r.memTotalBytes * 100).toFixed(1)) : null) },
              { name: '磁盘使用率', type: 'line', smooth: true, showSymbol: false,
                data: rows.map(r => r.diskTotalBytes ? Number((r.diskUsedBytes / r.diskTotalBytes * 100).toFixed(1)) : null) },
            ],
          });
        }
      });
    }

    async function showFundingRate(symbol) {
      frSymbol.value = symbol;
      frDialog.value = true;
      try {
        const res = await api.get('/api/admin/funding-rate-history', { params: { symbol, limit: 300 } });
        frHistory.value = res.data;
        nextTick(() => {
          const el = document.getElementById('fr-chart');
          if (!el) return;
          // 弹窗关闭再打开时节点会重建，同样需要重新初始化，否则第二次打开是空白
          if (frChart && frChart.getDom() !== el) {
            frChart.dispose();
            frChart = null;
          }
          if (!frChart) frChart = echarts.init(el);
          const rows = frHistory.value.slice().reverse(); // 时间升序
          frChart.setOption({
            tooltip: { trigger: 'axis' },
            grid: { left: 60, right: 20, top: 20, bottom: 40 },
            xAxis: { type: 'category', data: rows.map(r => (r.fundingTime || '').replace('T', ' ').slice(5, 16)) },
            yAxis: { type: 'value', scale: true },
            series: [{
              name: '资金费率', type: 'line', smooth: false, showSymbol: false,
              data: rows.map(r => Number(r.fundingRate)),
            }],
          });
        });
      } catch (e) {
        console.error(e);
      }
    }

    function levelTag(level) {
      return level === 'ERROR' ? 'danger' : level === 'WARN' ? 'warning' : 'info';
    }

    function statusText() {
      if (!overview.value) return '';
      if (overview.value.halted) return '熔断';
      return overview.value.mockEnabled ? '运行中（自建 mock）' : '运行中';
    }
    function statusType() {
      return overview.value && overview.value.halted ? 'danger' : 'success';
    }

    let lightTimer = null;
    let heavyTimer = null;
    onMounted(() => {
      loadLight();
      loadHeavy();
      lightTimer = setInterval(loadLight, 10000);
      heavyTimer = setInterval(loadHeavy, 30000);
      window.addEventListener('resize', () => {
        if (equityChart) equityChart.resize();
        if (frChart) frChart.resize();
        if (hostCpuChart) hostCpuChart.resize();
        if (hostMemChart) hostMemChart.resize();
      });
    });
    watch(active, (v) => {
      if (v === 'curve') nextTick(renderEquity);
      if (v === 'host') nextTick(renderHost);
    });
    onBeforeUnmount(() => {
      clearInterval(lightTimer);
      clearInterval(heavyTimer);
    });

    return {
      active, loading, overview, positions, candidates, orders, fills, fundingIncome,
      events, params, curve, pnl, host, hostHistory, frDialog, frSymbol, frHistory, tradeTab,
      pct, money, num, gb, memPct, diskPct, showFundingRate, levelTag, statusText, statusType,
    };
  },

  template: `
  <div class="layout">
    <aside class="sidebar">
      <div class="logo">quantification</div>
      <el-menu :default-active="active" @select="(k) => active = k" background-color="#1f2d3d"
               text-color="#cfd8e3" active-text-color="#409eff">
        <el-menu-item index="overview">总览</el-menu-item>
        <el-menu-item index="positions">持仓</el-menu-item>
        <el-menu-item index="candidates">候选池</el-menu-item>
        <el-menu-item index="trades">成交与订单</el-menu-item>
        <el-menu-item index="curve">收益曲线</el-menu-item>
        <el-menu-item index="host">机器监控</el-menu-item>
        <el-menu-item index="events">异常与告警</el-menu-item>
        <el-menu-item index="params">当前参数</el-menu-item>
      </el-menu>
    </aside>

    <main class="main">
      <!-- 总览 -->
      <div v-if="active === 'overview'">
        <div class="card">
          <div style="display:flex;justify-content:space-between;align-items:center">
            <h3 style="margin:0">总览</h3>
            <el-tag v-if="overview" :type="statusType()">{{ statusText() }}</el-tag>
          </div>
          <el-alert v-if="overview && overview.halted" :title="'已熔断：' + overview.haltReason"
                    type="error" :closable="false" style="margin-top:12px" />
          <div class="metric" style="margin-top:16px">
            <div class="item"><div class="label">账户总权益(USDT)</div><div class="value">{{ money(overview?.accountEquity) }}</div></div>
            <div class="item"><div class="label">有效权益(USDT)</div><div class="value">{{ money(overview?.effEquity) }}</div></div>
            <div class="item"><div class="label">维持保证金率</div><div class="value">{{ pct(overview?.mgnRatio) }}</div></div>
            <div class="item"><div class="label">仓位名义价值</div><div class="value">{{ money(overview?.positionValue) }}</div></div>
            <div class="item"><div class="label">持仓币种数</div><div class="value">{{ overview?.holdingsCount ?? '-' }}</div></div>
            <div class="item"><div class="label">累计资金费</div><div class="value">{{ money(overview?.fundingIncome) }}</div></div>
            <div class="item"><div class="label">累计手续费</div><div class="value">{{ money(overview?.feeCost) }}</div></div>
            <div class="item"><div class="label">累计收益率</div><div class="value">{{ pct(overview?.cumulativeReturn) }}</div></div>
            <div class="item"><div class="label">滚动年化</div><div class="value">{{ pct(overview?.rollingAnnualized) }}</div></div>
          </div>
          <div style="margin-top:12px;color:#8492a6;font-size:12px">
            订单 {{ overview?.orderCount ?? 0 }} 笔 · 成交 {{ overview?.fillCount ?? 0 }} 笔 ·
            最近净值快照 {{ overview?.lastSnapshotTime ? overview.lastSnapshotTime.replace('T',' ') : '-' }}
          </div>
        </div>
        <div class="card" v-if="pnl">
          <h3 style="margin-top:0">盈亏核算（损耗分解）</h3>
          <div class="metric">
            <div class="item"><div class="label">初始资金</div><div class="value">{{ money(pnl.initialUsdt) }}</div></div>
            <div class="item"><div class="label">当前权益</div><div class="value">{{ money(pnl.equity) }}</div></div>
            <div class="item"><div class="label">权益变动</div><div class="value">{{ money(pnl.equity - pnl.initialUsdt) }}</div></div>
            <div class="item"><div class="label">手续费</div><div class="value" style="color:#e6a23c">−{{ money(pnl.feeCost) }}</div></div>
            <div class="item"><div class="label">资金费</div><div class="value" style="color:#67c23a">+{{ money(pnl.fundingIncome) }}</div></div>
            <div class="item"><div class="label">现货已实现</div><div class="value">{{ money(pnl.spotRealized) }}</div></div>
            <div class="item"><div class="label">永续已实现</div><div class="value">{{ money(pnl.perpRealized) }}</div></div>
            <div class="item"><div class="label">未实现</div><div class="value">{{ money(pnl.unrealized) }}</div></div>
            <div class="item"><div class="label">校验残差</div><div class="value" :title="'应接近 0（舍入误差）'">{{ num(pnl.residual, 6) }}</div></div>
          </div>
          <div style="margin-top:8px;color:#8492a6;font-size:12px">
            恒等式：权益变动 = 资金费 − 手续费 + 现货已实现 + 永续已实现 + 未实现；残差应接近 0。
          </div>
        </div>
      </div>

      <!-- 持仓 -->
      <div v-else-if="active === 'positions'" class="card">
        <h3 style="margin-top:0">持仓</h3>
        <el-table :data="positions" stripe>
          <el-table-column prop="baseCoin" label="币种" width="100" />
          <el-table-column prop="spotQty" label="现货数量"><template #default="s">{{ num(s.row.spotQty,6) }}</template></el-table-column>
          <el-table-column prop="perpQty" label="永续空头"><template #default="s">{{ num(s.row.perpQty,6) }}</template></el-table-column>
          <el-table-column prop="perpAvgPrice" label="空头均价"><template #default="s">{{ num(s.row.perpAvgPrice) }}</template></el-table-column>
          <el-table-column prop="lastPrice" label="最新价"><template #default="s">{{ num(s.row.lastPrice) }}</template></el-table-column>
          <el-table-column prop="unrealisedPnl" label="未实现盈亏"><template #default="s">{{ money(s.row.unrealisedPnl) }}</template></el-table-column>
          <el-table-column prop="fundingIncome" label="累计资金费"><template #default="s">{{ money(s.row.fundingIncome) }}</template></el-table-column>
          <el-table-column prop="feeCost" label="累计手续费"><template #default="s">{{ money(s.row.feeCost) }}</template></el-table-column>
          <el-table-column prop="openedAt" label="开仓时间" width="180"><template #default="s">{{ s.row.openedAt ? s.row.openedAt.replace('T',' ') : '-' }}</template></el-table-column>
        </el-table>
      </div>

      <!-- 候选池 -->
      <div v-else-if="active === 'candidates'" class="card">
        <h3 style="margin-top:0">候选池</h3>
        <el-table :data="candidates" stripe :default-sort="{prop:'netAnnualizedPct', order:'descending'}">
          <el-table-column prop="baseCoin" label="币种" width="100" />
          <el-table-column prop="symbol" label="交易对" width="140" />
          <el-table-column prop="intervalHours" label="周期(h)" width="80" />
          <el-table-column prop="grossAnnualizedPct" label="90天毛年化" sortable><template #default="s">{{ s.row.grossAnnualizedPct }}%</template></el-table-column>
          <el-table-column prop="feeDragPct" label="成本年化" sortable><template #default="s">{{ s.row.feeDragPct }}%</template></el-table-column>
          <el-table-column prop="netAnnualizedPct" label="90天净年化" sortable><template #default="s">{{ s.row.netAnnualizedPct }}%</template></el-table-column>
          <el-table-column prop="discountRate" label="折扣率"><template #default="s">{{ s.row.discountRate ?? '-' }}</template></el-table-column>
          <el-table-column prop="turnover24h" label="24h成交额"><template #default="s">{{ s.row.turnover24h ? money(s.row.turnover24h) : '-' }}</template></el-table-column>
          <el-table-column prop="allNetAnnualizedPct" label="全历史净年化" sortable>
            <template #default="s">
              <span v-if="s.row.allNetAnnualizedPct != null">{{ s.row.allNetAnnualizedPct }}%<span style="color:#8492a6;font-size:12px"> ({{ s.row.allDays }}天)</span></span>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="s"><el-button size="small" @click="showFundingRate(s.row.symbol)">费率曲线</el-button></template>
          </el-table-column>
        </el-table>
        <el-dialog v-model="frDialog" :title="frSymbol + ' 历史资金费率'" width="70%">
          <div id="fr-chart" style="height:360px"></div>
        </el-dialog>
      </div>

      <!-- 成交与订单 -->
      <div v-else-if="active === 'trades'" class="card">
        <h3 style="margin-top:0">成交与订单</h3>
        <el-tabs v-model="tradeTab">
          <el-tab-pane label="成交明细" name="fills">
            <el-table :data="fills" stripe>
              <el-table-column prop="symbol" label="交易对" width="130" />
              <el-table-column prop="leg" label="腿" width="70" />
              <el-table-column prop="side" label="方向" width="70" />
              <el-table-column prop="tradeSide" label="开/平" width="70" />
              <el-table-column prop="execPrice" label="成交价"><template #default="s">{{ num(s.row.execPrice) }}</template></el-table-column>
              <el-table-column prop="execQty" label="数量"><template #default="s">{{ num(s.row.execQty,6) }}</template></el-table-column>
              <el-table-column prop="execValue" label="金额"><template #default="s">{{ money(s.row.execValue) }}</template></el-table-column>
              <el-table-column prop="fee" label="手续费"><template #default="s">{{ money(s.row.fee) }}</template></el-table-column>
              <el-table-column prop="slippage" label="滑点"><template #default="s">{{ pct(s.row.slippage) }}</template></el-table-column>
              <el-table-column prop="createdTime" label="时间" width="180"><template #default="s">{{ s.row.createdTime ? s.row.createdTime.replace('T',' ') : '-' }}</template></el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="订单" name="orders">
            <el-table :data="orders" stripe>
              <el-table-column prop="symbol" label="交易对" width="130" />
              <el-table-column prop="side" label="方向" width="70" />
              <el-table-column prop="orderType" label="类型" width="80" />
              <el-table-column prop="orderStatus" label="状态" width="100">
                <template #default="s">
                  <el-tag :type="s.row.orderStatus==='filled' ? 'success' : s.row.orderStatus==='rejected' ? 'danger' : 'info'">{{ s.row.orderStatus }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="qty" label="委托量"><template #default="s">{{ num(s.row.qty,6) }}</template></el-table-column>
              <el-table-column prop="cumExecQty" label="成交量"><template #default="s">{{ num(s.row.cumExecQty,6) }}</template></el-table-column>
              <el-table-column prop="avgPrice" label="均价"><template #default="s">{{ num(s.row.avgPrice) }}</template></el-table-column>
              <el-table-column prop="rejectReason" label="拒绝原因" min-width="160"><template #default="s">{{ s.row.rejectReason || '' }}</template></el-table-column>
              <el-table-column prop="exchangeCreatedTime" label="时间" width="180"><template #default="s">{{ s.row.exchangeCreatedTime ? s.row.exchangeCreatedTime.replace('T',' ') : '-' }}</template></el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="资金费" name="funding">
            <el-table :data="fundingIncome" stripe>
              <el-table-column prop="symbol" label="交易对" width="130" />
              <el-table-column prop="settlementTime" label="结算时间" width="180"><template #default="s">{{ s.row.settlementTime ? s.row.settlementTime.replace('T',' ') : '-' }}</template></el-table-column>
              <el-table-column prop="fundingRate" label="费率"><template #default="s">{{ num(s.row.fundingRate,8) }}</template></el-table-column>
              <el-table-column prop="positionQty" label="仓位"><template #default="s">{{ num(s.row.positionQty,4) }}</template></el-table-column>
              <el-table-column prop="fundingAmount" label="入账(USDT)"><template #default="s">{{ money(s.row.fundingAmount) }}</template></el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </div>

      <!-- 收益曲线 -->
      <div v-else-if="active === 'curve'" class="card">
        <h3 style="margin-top:0">收益曲线（模拟净值）</h3>
        <div id="equity-chart" class="chart"></div>
      </div>

      <!-- 异常与告警 -->
      <div v-else-if="active === 'events'" class="card">
        <h3 style="margin-top:0">异常与告警</h3>
        <el-table :data="events" stripe>
          <el-table-column prop="createdAt" label="时间" width="180"><template #default="s">{{ s.row.createdAt ? s.row.createdAt.replace('T',' ') : '-' }}</template></el-table-column>
          <el-table-column prop="level" label="级别" width="90">
            <template #default="s"><el-tag :type="levelTag(s.row.level)">{{ s.row.level }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="type" label="类型" width="110" />
          <el-table-column prop="title" label="标题" min-width="200" />
          <el-table-column prop="detail" label="详情" min-width="260" />
        </el-table>
      </div>

      <!-- 机器监控 -->
      <div v-else-if="active === 'host'">
        <div class="card">
          <h3 style="margin-top:0">机器监控（当前）</h3>
          <div class="metric">
            <div class="item"><div class="label">整机 CPU</div><div class="value">{{ host?.cpuLoadPct != null ? host.cpuLoadPct + '%' : '-' }}</div></div>
            <div class="item"><div class="label">本进程 CPU</div><div class="value">{{ host?.processCpuLoadPct != null ? host.processCpuLoadPct + '%' : '-' }}</div></div>
            <div class="item"><div class="label">CPU 核数</div><div class="value">{{ host?.cpuCores ?? '-' }}</div></div>
            <div class="item"><div class="label">负载(1分钟)</div><div class="value">{{ host?.loadAverage ?? '-' }}</div></div>
            <div class="item"><div class="label">内存</div><div class="value">{{ gb(host?.memUsedBytes) }} / {{ gb(host?.memTotalBytes) }}</div></div>
            <div class="item"><div class="label">内存使用率</div><div class="value">{{ memPct() }}</div></div>
            <div class="item"><div class="label">磁盘</div><div class="value">{{ gb(host?.diskUsedBytes) }} / {{ gb(host?.diskTotalBytes) }}</div></div>
            <div class="item"><div class="label">磁盘使用率</div><div class="value">{{ diskPct() }}</div></div>
            <div class="item"><div class="label">JVM 堆</div><div class="value">{{ gb(host?.heapUsedBytes) }} / {{ gb(host?.heapMaxBytes) }}</div></div>
            <div class="item"><div class="label">交换区已用</div><div class="value">{{ gb(host?.swapUsedBytes) }}</div></div>
          </div>
          <div style="margin-top:8px;color:#8492a6;font-size:12px">
            说明：macOS 会把空闲内存拿去做文件缓存，所以"内存使用率"接近 100% 属正常；判断内存是否吃紧看"交换区已用"是否持续增长。
            功耗无法在 macOS 上以普通权限读取（需要 root 的 powermetrics），这里用 CPU 使用率与负载作为代理指标。
          </div>
        </div>
        <div class="card">
          <h4 style="margin-top:0">CPU 使用率历史（%）</h4>
          <div id="host-cpu-chart" class="chart"></div>
        </div>
        <div class="card">
          <h4 style="margin-top:0">内存 / 磁盘 使用率历史（%）</h4>
          <div id="host-mem-chart" class="chart"></div>
        </div>
      </div>

      <!-- 当前参数 -->
      <div v-else-if="active === 'params'" class="card">
        <h3 style="margin-top:0">当前参数（只读）</h3>
        <el-table :data="params" stripe>
          <el-table-column prop="label" label="名称" width="180" />
          <el-table-column prop="key" label="配置项" min-width="220" />
          <el-table-column prop="value" label="当前值" width="170" />
          <el-table-column prop="note" label="说明" min-width="280" />
        </el-table>
      </div>
    </main>
  </div>
  `,
});

app.use(ElementPlus);
app.mount('#app');
