# Лабораторная 1 — Geo KD-tree (профилирование и тестирование производительности)

Порт C#-эталона (`lab-2-geo`, `Hw2.Algorithms`) на Java. Baseline — точная копия логики без оптимизаций.

Структура: корневой модуль — библиотека `org.timermakov.geo` (KD-tree), `:benchmarks` — JMH, `:profile-runner` — tight-loop прогон для профилировщика.

## Части задания

1. Geo KD-tree для точек `Lat/Lng`
2. Вставка объектов `Insert`
3. Пространственный поиск:
   - `searchRadius` (поиск в радиусе)
   - `searchKNearest` (k ближайших)

## Требования

- JDK 25 (LTS)
- Gradle — только через wrapper (`./gradlew`), запускать не через IDE-раннер

## Команды

Из каталога `lab-1-java-geo-kdtree`:

```
./gradlew build                # сборка + все тесты (включая perf smoke)
./gradlew test                 # тесты (property: 80 детерминированных кейсов vs full-scan oracle + unit + perf)
./gradlew :benchmarks:bench    # полный JMH-прогон (warmup 15 x 0.5s, 40 итераций — долго)
./gradlew :benchmarks:benchSmoke    # один быстрый прогон для проверки запуска (HW1_BENCH_SMOKE=1)
./gradlew :profile-runner:run --args="--mode radius --seconds 120 --n 100000 --radius 1000 --k 20"
```

## Конфигурация бенчмарков

Все параметры прогонов — в [`config/bench.settings.json`](config/bench.settings.json), не в коде:

| Поле | Значение по умолчанию | Смысл |
|---|---|---|
| `nValues` | `[10000, 21544, 35938]` | сетка размеров датасета (аналог `[Params]` в C#) |
| `kValues` | `[1, 20]` | параметры K для kNN |
| `radiusValues` | `[100, 5000]` | радиусы для radius-поиска |
| `launchCount` | `1` | число fork'ов JMH на бенчмарк |
| `warmupCount` | `15` | итераций warmup |
| `iterationCount` | `40` | измерительных итераций |
| `iterationTimeMs` | `500` | длительность одной итерации (паритет с BDN IterationTime) |
| `gcProfiler` | `true` | JMH gc-профайлер (аналог MemoryDiagnoser) |
| `resultDir` | `build/jmh-results` | каталог текстовых результатов |

Переопределения:
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

JFR (CPU-профиль + аллокации):

```
./gradlew :profile-runner:run --args="--mode radius --seconds 60" &
jcmd $(jps | awk '/ProfileRunner/{print $1}') JFR.start duration=60s filename=report/profiles/radius.jfr
```

async-profiler: приаттачиться к PID процесса `ProfileRunner` (см. `jps`), собрать flamegraph (`-e cpu -f flamegraph.html`). Артефакты — в `report/profiles/` (создаётся при первом прогоне).

## Baseline и оптимизации

Baseline повторяет семантику C#-версии, включая намеренные неэффективности:
- pruning far-ветки только по широте (`axisLowerBoundMeters` = 0 для longitude-сплитов);
- kNN-кандидаты хранятся в списке с re-sort на каждый кандидат (без heap);
- полная сортировка результатов после поиска.

Оптимизации производительности выполняются отдельно в perf-фазе (коммиты `perf:`), этот документ и код не должны смешивать оба этапа.

## Отчёт

Артефакты профилирования и сравнения — по мере выполнения perf-фазы в `report/`.
