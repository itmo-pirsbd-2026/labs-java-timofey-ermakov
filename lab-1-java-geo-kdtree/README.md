# Лабораторная 1 — Geo KD-tree (профилирование и тестирование производительности)

Структура: корневой модуль — библиотека `org.timermakov.geo` (KD-tree), `:benchmarks` — JMH, `:profile-runner` — tight-loop прогон для профилировщика.

1. Geo KD-tree для точек `Lat/Lng`
2. Вставка объектов `Insert`
3. Пространственный поиск:
   - `searchRadius` (поиск в радиусе)
   - `searchKNearest` (k ближайших)

## Команды

Из каталога `lab-1-java-geo-kdtree`:

```
./gradlew build                # сборка + все тесты (включая perf smoke)
./gradlew test                 # тесты (property: 80 детерминированных кейсов vs full-scan oracle + unit + perf)
./gradlew :benchmarks:bench    # полный JMH-прогон (warmup 15 x 0.5s, 40 итераций — долго)
./gradlew :benchmarks:benchPublic   # бенчмарки публичной библиотеки (Smile KDTree на ECEF-3D)
./gradlew :benchmarks:benchEcef     # бенчмарки экспериментального ECEF-ядра (см. report/smile-comparison.md)
./gradlew :benchmarks:benchSmoke    # один быстрый прогон для проверки запуска (HW1_BENCH_SMOKE=1)
./gradlew :profile-runner:run --args="--mode radius --seconds 120 --n 100000 --radius 1000 --k 20"
```

## Конфигурация бенчмарков

Все параметры прогонов — в [`config/bench.settings.json`](config/bench.settings.json), не в коде:

| Поле | Значение по умолчанию | Смысл |
|---|---|---|
| `nValues` | `[100, 200, 500, 1000, 2500, 5000, 10000, 20000, 35000, 50000, 75000, 100000]` | сетка размеров датасета (аналог `[Params]` в C#) |
| `kValues` | `[1, 20]` | параметры K для kNN |
| `radiusValues` | `[100, 5000]` | радиусы для radius-поиска |
| `launchCount` | `1` | число fork'ов JMH на бенчмарк |
| `warmupCount` | `15` | итераций warmup |
| `iterationCount` | `40` | измерительных итераций |
| `iterationTimeMs` | `500` | длительность одной итерации (паритет с BDN IterationTime) |
| `gcProfiler` | `true` | JMH gc-профайлер (аналог MemoryDiagnoser) |
| `resultDir` | `build/jmh-results` | каталог текстовых результатов |

Замеры цикла оптимизаций снимались в двух конфигурациях: baseline и финальное состояние — warmup 15 + 40 итераций (`config/bench.settings.json`), промежуточные состояния — warmup 10 + 20 итераций (переопределение `HW1_BENCH_CONFIG`). Переопределения:
- `HW1_BENCH_CONFIG=<путь>` — нестандартный путь к конфигу
- `HW1_BENCH_SMOKE=1` — smoke-режим (один кейс, без warmup; эквивалент `benchSmoke`)

Методы бенчмарков используют батчи операций (`@OperationsPerInvocation`, аналогично `OperationsPerInvoke` в C#): KD-tree 128, full-scan baseline 16/32. Результаты: `build/jmh-results/geo-kd-tree-knn.txt` и `geo-kd-tree-radius.txt`.

## Профайлинг

Чистый hotpath (`GeoKdTreeIndex`/`GeoDistance`) даёт `:profile-runner:run`:

```
# radius hotpath
./gradlew :profile-runner:run --args="--mode radius --seconds 120 --n 100000 --radius 1000"

# kNN hotpath
./gradlew :profile-runner:run --args="--mode knn --seconds 120 --n 100000 --k 20"
```

JFR-сборка + рендер флеймграфов тулингом async-profiler (`jfrconv`) — скрипт [tools/capture-jfr.sh](tools/capture-jfr.sh) (пути внутри рассчитаны на эту машину; JFR пишется JVM, конвертация — `java -jar tools/../.profiler-tools/.../jfrconv`, см. скрипт):

```
bash tools/capture-jfr.sh radius 30 my-radius-profile   # -> report/profiles/my-radius-profile.{jfr,html,collapsed}
```

async-profiler: приаттачиться к PID процесса `ProfileRunner` (см. `jps`), собрать flamegraph (`-e cpu -f flamegraph.html`). Артефакты — в `report/profiles/` (создаётся при первом прогоне).

## Baseline и оптимизации

Изначально я перенёс C# код на Java без оптимизаций.

Поверх него выполнено исследование производительности и оптимизации: отсечение дальних веток по долготе (seam-aware граница), сравнения в a-space без `sqrt`/`asin`, бинарная вставка в best-лист, кэш косинусов широт.

Итог на расширенной сетке (N до 100000, см. [report/perf.md](report/perf.md)): ускорение к baseline ×16.4–17.0 для radius и kNN k=1, ×6.6 для kNN k=20; финал быстрее полного перебора в 570–1075 раз на n=100000. Есть сравнение с C#-версией и с публичной библиотекой Smile (ECEF-3D).

## Отчёты

- [baseline.md](baseline.md) — анализ baseline-профилей (async-profiler-тулинг по JFR-сборкам) и план оптимизаций
- [report/perf.md](report/perf.md) — журнал perf-цикла: методика, замеры по каждому состоянию, сравнение с C#, выводы
- [report/smile-comparison.md](report/smile-comparison.md) — сравнение финала с публичной библиотекой Smile (ECEF): разбор разрыва, точность, план улучшения сравнения
- [report/related-work.md](report/related-work.md) — обзор существующих пространственных индексов (JTS, GEOS, S2, Lucene, JSI и др.) и рекомендуемые ориентиры для следующих сравнений
- `report/profiles/` — JFR-записи, HTML-флеймграфы, collapsed-стеки; `report/profiles/grid/` — по каждому состоянию на расширенной сетке; `report/profiles/images/` — скриншоты
- `report/bench/` — сырые JMH-результаты; `report/bench/grid/` — замеры по состояниям на расширенной сетке + сводные таблицы
- `report/charts/` — графики (throughput, speedup, кратности, Java против C#, против Smile), генератор — `tools/make_charts.py`
