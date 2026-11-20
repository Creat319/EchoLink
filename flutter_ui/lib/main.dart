import 'dart:async';
import 'dart:convert';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

/// EchoLink 的 Flutter UI。
///
/// 分工：**音频链路全部留在 Kotlin / C++**，这里只做两件事——
/// 订阅状态（EventChannel）和下命令（MethodChannel）。换 UI 框架
/// 因此不会给播放路径带来任何风险。
void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const EchoLinkApp());
}

// ---------------------------------------------------------------- 通道

const _commandChannel = MethodChannel('echolink/commands');
const _stateChannel = EventChannel('echolink/state');

class _Bridge {
  static Future<T?> call<T>(String method, [Map<String, Object?>? args]) async {
    try {
      return await _commandChannel.invokeMethod<T>(method, args);
    } on PlatformException catch (e) {
      debugPrint('bridge $method failed: ${e.message}');
      return null;
    }
  }
}

// ---------------------------------------------------------------- 状态模型

class Engine {
  const Engine(this.raw);
  final Map<String, dynamic> raw;

  double d(String k) => (raw[k] as num?)?.toDouble() ?? 0;
  int i(String k) => (raw[k] as num?)?.toInt() ?? 0;
  bool b(String k) => raw[k] == true;

  double get waterMs => d('waterMs');
  double get bufferMs => d('bufferMs');
  int get sampleRate => i('sampleRate');
  int get channels => i('channels');
  int get framesPerBurst => i('framesPerBurst');
  int get bufferSizeFrames => i('bufferSizeFrames');
  double get latencyMs => d('latencyMs');
  bool get exclusive => b('exclusive');
  int get exclusiveDeniedCode => i('exclusiveDeniedCode');
  double get maxCallbackGapMs => d('maxCallbackGapMs');
  double get schedGapMaxMs => d('schedGapMaxMs');
  double get ratio => d('ratio');
  bool get mmapSupported => b('mmapSupported');
  bool get mmapUsed => b('mmapUsed');
  double get underrunEvents => d('underrunEvents');
  double get underrunFrames => d('underrunFrames');
  double get readStarveFrames => d('readStarveFrames');
  int get xRunCount => i('xRunCount');
  double get silenceFilledFrames => d('silenceFilledFrames');
  double get dropEvents => d('dropEvents');
  double get dropFrames => d('dropFrames');
  double get resyncEvents => d('resyncEvents');
  double get framesPlayed => d('framesPlayed');
}

class Network {
  const Network(this.raw);
  final Map<String, dynamic> raw;

  double d(String k) => (raw[k] as num?)?.toDouble() ?? 0;

  double get packetsReceived => d('packetsReceived');
  double get packetsLost => d('packetsLost');
  double get packetsOutOfOrder => d('packetsOutOfOrder');
  double get packetsMalformed => d('packetsMalformed');
  double get packetsPerSecond => d('packetsPerSecond');
  String? get senderAddress => raw['senderAddress'] as String?;
  double get stallEvents => d('stallEvents');
  double get stallMaxMs => d('stallMaxMs');
}

class LinkState {
  const LinkState(this.raw);
  final Map<String, dynamic> raw;

  static const empty = LinkState(<String, dynamic>{});

  Map<String, dynamic> get _e =>
      (raw['engine'] as Map?)?.cast<String, dynamic>() ?? const {};
  Map<String, dynamic> get _n =>
      (raw['network'] as Map?)?.cast<String, dynamic>() ?? const {};
  Map<String, dynamic> get _s =>
      (raw['settings'] as Map?)?.cast<String, dynamic>() ?? const {};

  Engine get engine => Engine(_e);
  Network get network => Network(_n);

  String get phase => (raw['phase'] as String?) ?? 'STOPPED';
  String get statusText => (raw['statusText'] as String?) ?? '';
  bool get isRunning => phase != 'STOPPED';

  double d(String k) => (raw[k] as num?)?.toDouble() ?? 0;
  int i(String k) => (raw[k] as num?)?.toInt() ?? 0;

  double get targetWaterMs => d('targetWaterMs');
  double get waterMinMs => d('waterMinMs');
  double get waterAvgMs => d('waterAvgMs');
  int get bufferBursts => i('bufferBursts');
  String get updateCheckText => (raw['updateCheckText'] as String?) ?? '';

  List<String> get log =>
      (raw['log'] as List?)?.map((e) => e.toString()).toList() ?? const [];
  List<String> get localAddresses =>
      (raw['localAddresses'] as List?)?.map((e) => e.toString()).toList() ?? const [];

  int get port => (_s['port'] as num?)?.toInt() ?? 47810;
  bool get requestExclusive => _s['requestExclusive'] == true;
  bool get autoTargetWater => _s['autoTargetWater'] == true;
}

// ---------------------------------------------------------------- 主题色

const _bg = Color(0xFF0A0E13);
const _surface = Color(0xFF111822);
const _onSurface = Color(0xFFE4EBF2);
const _onSurfaceVariant = Color(0xFFA8B6C6);
const _primary = Color(0xFF64D2FF);
const _secondary = Color(0xFF7EE2B8);
const _tertiary = Color(0xFFFFC46B);
const _error = Color(0xFFFF7A7A);

class EchoLinkApp extends StatefulWidget {
  const EchoLinkApp({super.key});

  @override
  State<EchoLinkApp> createState() => _EchoLinkAppState();
}

class _EchoLinkAppState extends State<EchoLinkApp> {
  LinkState _state = LinkState.empty;
  StreamSubscription<dynamic>? _sub;
  String _version = '';
  int _tab = 0;

  @override
  void initState() {
    super.initState();
    _sub = _stateChannel.receiveBroadcastStream().listen((event) {
      if (!mounted) return;
      final map = jsonDecode(event as String) as Map<String, dynamic>;
      setState(() => _state = LinkState(map));
    }, onError: (Object e) => debugPrint('state stream error: $e'));

    _Bridge.call<String>('getVersion').then((v) {
      if (mounted && v != null) setState(() => _version = v);
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'EchoLink',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: _bg,
        colorScheme: const ColorScheme.dark(
          primary: _primary,
          secondary: _secondary,
          tertiary: _tertiary,
          surface: _surface,
          error: _error,
        ),
      ),
      home: Scaffold(
        backgroundColor: _bg,
        appBar: AppBar(
          backgroundColor: _bg,
          elevation: 0,
          titleSpacing: 16,
          title: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('EchoLink',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
              Text(
                _state.statusText.isEmpty ? '等待数据' : _state.statusText,
                style: const TextStyle(fontSize: 12, color: _onSurfaceVariant),
              ),
            ],
          ),
          actions: [
            Padding(
              padding: const EdgeInsets.only(right: 14),
              child: Center(
                child: Text(
                  'v$_version',
                  style: const TextStyle(fontSize: 11, color: _onSurfaceVariant),
                ),
              ),
            ),
          ],
        ),
        body: IndexedStack(
          index: _tab,
          children: [
            _HomeTab(state: _state),
            _SettingsTab(state: _state),
            _LogTab(state: _state),
          ],
        ),
        bottomNavigationBar: _GlassNav(
          index: _tab,
          onSelect: (i) => setState(() => _tab = i),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------- 液态玻璃 dock

/// 视觉复刻 FlClash / prism_ui 的 `LiquidGlassStyle.pill`。
/// 原实现是 GLSL 着色器做真实背景折射；这里用「半透明渐变 + 边缘 rim +
/// 顶部内高光」的等效画法，深色背景下观感接近。
class _GlassNav extends StatelessWidget {
  const _GlassNav({required this.index, required this.onSelect});

  final int index;
  final ValueChanged<int> onSelect;

  static const _items = <(IconData, String)>[
    (Icons.home_rounded, '连接'),
    (Icons.settings_rounded, '设置'),
    (Icons.list_rounded, '日志'),
  ];

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 6, 16, 10),
        child: Container(
          height: 58,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(29),
            gradient: const LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [Color(0x22FFFFFF), Color(0x0FFFFFFF)],
            ),
            border: Border.all(color: Colors.white.withValues(alpha: 0.16), width: 1.2),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.45),
                blurRadius: 24,
                offset: const Offset(0, 10),
              ),
            ],
          ),
          child: Stack(
            children: [
              // 顶部内高光：玻璃感的关键一笔
              Positioned(
                top: 1.5,
                left: 40,
                right: 40,
                child: Container(
                  height: 1,
                  decoration: BoxDecoration(
                    gradient: LinearGradient(colors: [
                      Colors.white.withValues(alpha: 0),
                      Colors.white.withValues(alpha: 0.30),
                      Colors.white.withValues(alpha: 0),
                    ]),
                  ),
                ),
              ),
              Row(
                children: List.generate(_items.length, (i) {
                  final selected = i == index;
                  final color =
                      selected ? _primary : _onSurfaceVariant.withValues(alpha: 0.75);
                  return Expanded(
                    child: InkWell(
                      borderRadius: BorderRadius.circular(29),
                      onTap: () => onSelect(i),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(_items[i].$1, size: 22, color: color),
                          const SizedBox(height: 3),
                          Text(_items[i].$2,
                              style: TextStyle(fontSize: 11, color: color)),
                        ],
                      ),
                    ),
                  );
                }),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------- 卡片与行

class _GlassCard extends StatelessWidget {
  const _GlassCard({required this.title, required this.children, this.accent});

  final String title;
  final List<Widget> children;
  final Color? accent;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 14),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(18),
        gradient: const LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [_surface, Color(0xB3111822)],
        ),
        border: Border.all(color: Colors.white.withValues(alpha: 0.07)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              if (accent != null) ...[
                Container(
                  width: 4,
                  height: 14,
                  decoration: BoxDecoration(
                    color: accent,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
                const SizedBox(width: 8),
              ],
              Text(
                title,
                style: const TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                  color: _onSurfaceVariant,
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          ...children,
        ],
      ),
    );
  }
}

class _Metric extends StatelessWidget {
  const _Metric({
    required this.label,
    required this.value,
    this.hint,
    this.valueColor,
  });

  final String label;
  final String value;
  final String? hint;
  final Color? valueColor;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            flex: 4,
            child: Text(label,
                style: const TextStyle(fontSize: 13, color: _onSurfaceVariant)),
          ),
          Expanded(
            flex: 5,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text(
                  value,
                  textAlign: TextAlign.right,
                  style: TextStyle(
                    fontSize: 13,
                    color: valueColor ?? _onSurface,
                    fontFeatures: const [FontFeature.tabularFigures()],
                  ),
                ),
                if (hint != null)
                  Text(
                    hint!,
                    textAlign: TextAlign.right,
                    style: TextStyle(
                      fontSize: 10,
                      color: _onSurfaceVariant.withValues(alpha: 0.6),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------- 三个页面

class _HomeTab extends StatelessWidget {
  const _HomeTab({required this.state});

  final LinkState state;

  @override
  Widget build(BuildContext context) {
    final e = state.engine;
    final n = state.network;
    return ListView(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 16),
      children: [
        _GlassCard(
          title: '状态',
          accent: _primary,
          children: [
            Row(
              children: [
                Container(
                  width: 8,
                  height: 8,
                  decoration: BoxDecoration(
                    color: state.isRunning ? _secondary : _onSurfaceVariant,
                    shape: BoxShape.circle,
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    state.statusText.isEmpty ? '等待数据' : state.statusText,
                    style: const TextStyle(fontSize: 13, color: _onSurface),
                  ),
                ),
                FilledButton.tonal(
                  onPressed: () =>
                      _Bridge.call<bool>(state.isRunning ? 'stop' : 'start'),
                  child: Text(state.isRunning ? '停止' : '开始接收'),
                ),
              ],
            ),
            const SizedBox(height: 6),
            _Metric(
                label: '目标水位',
                value: '${state.targetWaterMs.toStringAsFixed(1)} ms'),
            _Metric(
              label: '水位最低（1s）',
              value: '${state.waterMinMs.toStringAsFixed(2)} ms',
              valueColor: state.waterMinMs < state.targetWaterMs * 0.6
                  ? _error
                  : null,
            ),
            _Metric(
                label: '水位平均（1s）',
                value: '${state.waterAvgMs.toStringAsFixed(2)} ms'),
            _Metric(label: '当前水位', value: '${e.waterMs.toStringAsFixed(2)} ms'),
            _Metric(
                label: '重采样比率',
                value: '${(e.ratio * 100).toStringAsFixed(4)} %'),
          ],
        ),
        _GlassCard(
          title: '播放链路',
          accent: _secondary,
          children: [
            _Metric(label: '采样率', value: '${e.sampleRate} Hz'),
            _Metric(label: '声道', value: '${e.channels}'),
            _Metric(label: 'burst', value: '${e.framesPerBurst} 帧'),
            _Metric(
              label: 'Oboe 缓冲',
              value:
                  '${e.bufferSizeFrames} 帧 / ${e.bufferMs.toStringAsFixed(1)} ms',
              hint: '${state.bufferBursts} burst',
            ),
            _Metric(
              label: 'MMAP',
              value: e.mmapUsed
                  ? '正在使用'
                  : (e.mmapSupported ? '支持但未使用' : '设备不支持'),
            ),
            _Metric(
                label: '输出延迟', value: '${e.latencyMs.toStringAsFixed(2)} ms'),
            _Metric(
              label: '共享模式',
              value: e.exclusive ? '独占' : '共享',
              hint: e.exclusive
                  ? null
                  : (e.exclusiveDeniedCode != 0
                      ? '独占失败 code=${e.exclusiveDeniedCode}'
                      : null),
            ),
            _Metric(
                label: '回调间隔峰值',
                value: '${e.schedGapMaxMs.toStringAsFixed(1)} ms'),
            _Metric(
                label: '回调最大间隔',
                value: '${e.maxCallbackGapMs.toStringAsFixed(1)} ms'),
          ],
        ),
        _GlassCard(
          title: '网络',
          accent: _tertiary,
          children: [
            _Metric(label: '发送端', value: n.senderAddress ?? '尚未收到数据'),
            _Metric(label: '收包', value: n.packetsReceived.toStringAsFixed(0)),
            _Metric(
                label: '收包速率',
                value: '${n.packetsPerSecond.toStringAsFixed(1)} 包/秒'),
            _Metric(
              label: '丢包',
              value: n.packetsLost.toStringAsFixed(0),
              valueColor: n.packetsLost > 0 ? _tertiary : null,
            ),
            _Metric(
                label: '乱序', value: n.packetsOutOfOrder.toStringAsFixed(0)),
            _Metric(
              label: '畸形包',
              value: n.packetsMalformed.toStringAsFixed(0),
              valueColor: n.packetsMalformed > 0 ? _error : null,
            ),
            _Metric(
              label: '网络成串停顿',
              value:
                  '${n.stallEvents.toStringAsFixed(0)} 次 / 最长 ${n.stallMaxMs.toStringAsFixed(0)} ms',
              valueColor: n.stallEvents > 0 ? _error : null,
              hint: n.stallEvents > 0 ? 'WiFi 热点不稳？优先改用 USB 网络共享' : null,
            ),
          ],
        ),
        _GlassCard(
          title: '稳定性',
          accent: _error,
          children: [
            _Metric(
              label: '欠载次数',
              value: e.underrunEvents.toStringAsFixed(0),
              valueColor: e.underrunEvents > 0 ? _error : null,
            ),
            _Metric(
                label: '欠载补静音',
                value: '${e.underrunFrames.toStringAsFixed(0)} 帧'),
            _Metric(
                label: '├ 读端饥饿',
                value: '${e.readStarveFrames.toStringAsFixed(0)} 帧'),
            _Metric(
              label: '└ 硬件 xrun',
              value: e.xRunCount < 0 ? '不可用' : '${e.xRunCount} 次',
            ),
            _Metric(
                label: '缺包补静音',
                value: '${e.silenceFilledFrames.toStringAsFixed(0)} 帧'),
            _Metric(
                label: '积压丢帧',
                value:
                    '${e.dropEvents.toStringAsFixed(0)} 次 / ${e.dropFrames.toStringAsFixed(0)} 帧'),
            _Metric(
                label: '时间线重建', value: e.resyncEvents.toStringAsFixed(0)),
            _Metric(
                label: '已播放',
                value: '${(e.framesPlayed / 48000).toStringAsFixed(1)} s'),
          ],
        ),
      ],
    );
  }
}

class _SettingsTab extends StatelessWidget {
  const _SettingsTab({required this.state});

  final LinkState state;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 16),
      children: [
        _GlassCard(
          title: '本机地址（PC 端手动填这个）',
          children: state.localAddresses.isEmpty
              ? [
                  const Text('未检测到可用 IPv4 地址',
                      style: TextStyle(fontSize: 12, color: _onSurfaceVariant))
                ]
              : state.localAddresses
                  .map((a) => Padding(
                        padding: const EdgeInsets.symmetric(vertical: 2),
                        child: SelectableText(a,
                            style:
                                const TextStyle(fontSize: 13, color: _primary)),
                      ))
                  .toList(),
        ),
        _GlassCard(
          title: '设置',
          children: [
            _Metric(label: 'UDP 端口', value: '${state.port}'),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              dense: true,
              title: const Text('独占模式', style: TextStyle(fontSize: 13)),
              subtitle: const Text('拿不到会自动回退共享',
                  style: TextStyle(fontSize: 11)),
              value: state.requestExclusive,
              onChanged: (v) => _Bridge.call<bool>(
                  'updateSettings', {'requestExclusive': v}),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              dense: true,
              title: const Text('自动目标水位', style: TextStyle(fontSize: 13)),
              value: state.autoTargetWater,
              onChanged: (v) =>
                  _Bridge.call<bool>('updateSettings', {'autoTargetWater': v}),
            ),
            const SizedBox(height: 6),
            Text(
              '端口与独占模式的改动在下次「开始接收」时生效。',
              style: TextStyle(
                  fontSize: 11,
                  color: _onSurfaceVariant.withValues(alpha: 0.7)),
            ),
          ],
        ),
        _GlassCard(
          title: '版本',
          children: [
            _Metric(
              label: '更新状态',
              value: state.updateCheckText.isEmpty ? '尚未检查' : state.updateCheckText,
            ),
          ],
        ),
      ],
    );
  }
}

class _LogTab extends StatelessWidget {
  const _LogTab({required this.state});

  final LinkState state;

  @override
  Widget build(BuildContext context) {
    final log = state.log.reversed.toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
          child: Text(
            '诊断日志（仅中文）—— 技术排查用',
            style: TextStyle(
                fontSize: 11, color: _onSurfaceVariant.withValues(alpha: 0.7)),
          ),
        ),
        Expanded(
          child: ListView.builder(
            padding: const EdgeInsets.fromLTRB(12, 4, 12, 16),
            itemCount: log.length,
            itemBuilder: (_, i) => Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Text(
                log[i],
                style: const TextStyle(
                  fontSize: 11,
                  height: 1.45,
                  fontFamily: 'monospace',
                  color: _onSurfaceVariant,
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}
