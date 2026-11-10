# Delta 1kx chart indicators

The indicators on the phone and Windows charts, what each mark means, and how to use them.
All of them are estimated from the chart's own candles and volume; none of them is venue data.

Where to switch them:

- **Phone:** the chip bar above the chart (tap **Chart** to fold it open). **Liq ▾** opens quick settings.
  Settings › Chart has the rest.
- **Windows:** the **Indicators** menu in the chart toolbar. It applies to every chart in the grid.

## Liquidation levels

Where positions were opened, and the price at which each leverage tier of them gets liquidated.
Liquidations are forced market orders: shorts liquidating means forced buying, longs liquidating
means forced selling. Price tends to be pulled towards those prices and to spike through them.

### Where the levels come from

| Source | What it is | Why positions sit there |
|---|---|---|
| **Range** (faint box) | The market coiled: the 14-bar ATR stayed well under the 100-bar ATR for 12+ bars. | Positions build up quietly inside a range. |
| **▼ flush** | A red bar at least 2× the ATR on at least 3× normal volume, closing near its low. | Longs were forced out; dip buyers and late shorts opened there. |
| **▲ pump** | The same in green. | Shorts were squeezed; breakout longs and faders opened there. |

Each source's **volume-weighted average price** is where its positions were opened on average
("range avg" / "flush avg" on the newest one). From it, each tier's liquidation price is:

- long liquidation = average × (1 − (1/leverage − 0.5%))
- short liquidation = average × (1 + (1/leverage − 0.5%))

So 100x levels sit 0.5% from the average, 50x 1.5%, 25x 3.5% and 10x 9.5%.

### Reading the marks

| Mark | Meaning |
|---|---|
| Solid line | The level still holds: no candle has closed clearly through it. |
| Dots on the line | Hits: a candle wicked into the level and closed back. |
| **×3** in the label | Hit 3 times while holding. Repeated hits = a defended level; a clean break tends to run. |
| Thick line with a faint band | An active area: hit twice or more, or stacked. |
| **3 stacked** | Three sources put a level within 0.1% of this price. More positions liquidate there. |
| `--------\|` | The level broke: a candle closed beyond it by more than 0.3× ATR. Most of its liquidations have likely fired. |
| `\|--------` | Back in play: price returned to retest a broken level from the other side. |
| **· range 8h** / **· flush 1d** | The level comes from an older source, ended that long ago. Older sources are fainter and only show what still holds. |
| ▲ / ▼ tag at the chart edge | The nearest holding level above or below that is off screen, with its distance. |
| Colour | Leverage tier: yellow 100x, orange 50x, deep orange 25x, red 10x. |
| "Compressing…" | A range is building now. Its levels appear once it breaks. |

**Tap (phone) or click (Windows) a level's ⓘ label** for a card with its price, its distance in %
and in ATRs, where it came from, whether it held or broke, and a short read of what it means for a
long or a short.

### Using them

- **Short liquidations above the price** (forced buying): price is often drawn up to them and can
  spike through, then stall. As a long, a natural take-profit just under the level. As a short,
  don't put your stop just below it.
- **Long liquidations below the price** (forced selling): price is often drawn down, flushes through,
  then bounces. As a short, a take-profit just above the level. As a long, keep your stop clear of
  it, or wait for the flush to buy.
- **Broken levels** have little fuel left; on a retest they can act as support or resistance.
- **Compressed range forming:** when it breaks, the new levels on the break side are the first
  targets.

### Settings

- **Leverage tiers:** which tiers to draw (default 25x, 50x, 100x; the phone also offers 10x).
- **Sources kept:** how many past ranges and how many past flushes/pumps (default 2 of each).
- **Flushes and pumps:** also use forced moves as sources.
- **Pools heat:** a heat strip on the right edge with every candle's estimated liquidations that
  price has not traded through yet. Off by default.
- **Leverage bands:** the classic rolling version: liquidation lines around a moving VWMA or VWAP.
  Off by default.

## Volume profile

Volume at each price, split into buying (green) and selling (red) by where each candle closed in
its range. The **POC** is the busiest price; **VAH / VAL** bound the 70% value area. **nPOC** marks
earlier days' POCs that price has not traded through since; untested POCs often act as magnets.
On Windows, small orange ticks beside the profile mark low-volume nodes, where price tends to move fast.

## Sessions and killzones

- **Killzones** (strip along the bottom, chip **KZ** on the phone): Asia 20:00–00:00 New York,
  London 07:00–10:00 London, NY AM 07:00–10:00 New York, London close 10:00–12:00 New York.
  The strip fills in from light to full colour as the window runs.
- **Market hours** (strip along the top, chip **Sess** on the phone): Tokyo, London and New York
  cash sessions, weekdays, plus a grey weekend band.
- All windows follow each city's own daylight saving time.
- On Windows, the **Sessions** tab beside Trades shows a clock, countdowns to each window, the next
  daily/weekly/monthly opens and the nearest levels with their distance.

## Opens and previous day

Daily, weekly and monthly opens (UTC midnight), the New York midnight open, and the previous day's
high and low. Each line only runs through its own period. The current one is bold; levels further
than 3% from the price are drawn faint.
