import { useEffect, useRef } from 'react';
import * as d3 from 'd3';
import type { Provider } from '@/data/types';

interface DayBucket {
  date: string;
  byProvider: Record<string, number>;
}

interface StackedBarChartProps {
  data: DayBucket[];
  providers: Provider[];
  height?: number;
}

export function ProviderStackedBar({ data, providers, height = 200 }: StackedBarChartProps) {
  const svgRef = useRef<SVGSVGElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!svgRef.current || !containerRef.current || data.length === 0) return;

    const containerWidth = containerRef.current.clientWidth;
    const margin = { top: 10, right: 16, bottom: 32, left: 56 };
    const w = containerWidth - margin.left - margin.right;
    const h = height - margin.top - margin.bottom;

    const svg = d3.select(svgRef.current);
    svg.selectAll('*').remove();
    svg.attr('width', containerWidth).attr('height', height);

    const g = svg.append('g').attr('transform', `translate(${margin.left},${margin.top})`);

    const providerIds = providers.map(p => p.id);
    const colorMap = Object.fromEntries(providers.map(p => [p.id, p.color]));

    const stack = d3.stack<DayBucket>()
      .keys(providerIds)
      .value((d, key) => d.byProvider[key] ?? 0);

    const stacked = stack(data);

    const x = d3.scaleBand()
      .domain(data.map(d => d.date))
      .range([0, w])
      .padding(0.15);

    const maxVal = d3.max(stacked, layer => d3.max(layer, d => d[1])) ?? 0;
    const y = d3.scaleLinear().domain([0, maxVal]).nice().range([h, 0]);

    // Grid
    g.append('g')
      .call(d3.axisLeft(y).tickSize(-w).tickFormat(() => '').ticks(5))
      .call(gg => {
        gg.selectAll('line').attr('stroke', '#e2e8f0').attr('stroke-dasharray', '3,3');
        gg.select('.domain').remove();
      });

    // Bars
    g.selectAll('.layer')
      .data(stacked)
      .join('g')
      .attr('class', 'layer')
      .attr('fill', d => colorMap[d.key] ?? '#94a3b8')
      .selectAll('rect')
      .data(d => d)
      .join('rect')
      .attr('x', d => x(d.data.date) ?? 0)
      .attr('y', d => y(d[1]))
      .attr('height', d => Math.max(0, y(d[0]) - y(d[1])))
      .attr('width', x.bandwidth())
      .attr('rx', 2);

    // X Axis — show every 5th label
    const tickValues = data.filter((_, i) => i % 5 === 0).map(d => d.date);
    g.append('g')
      .attr('transform', `translate(0,${h})`)
      .call(
        d3.axisBottom(x)
          .tickValues(tickValues)
          .tickFormat(d => {
            const parts = (d as string).split('-');
            return `${parts[1]}/${parts[2]}`;
          }),
      )
      .call(gg => {
        gg.select('.domain').attr('stroke', '#cbd5e1');
        gg.selectAll('text').attr('fill', '#94a3b8').attr('font-size', '11');
        gg.selectAll('line').attr('stroke', '#cbd5e1');
      });

    // Y Axis
    g.append('g')
      .call(
        d3.axisLeft(y).ticks(5).tickFormat(v => `$${(v as number).toFixed(0)}`),
      )
      .call(gg => {
        gg.select('.domain').remove();
        gg.selectAll('text').attr('fill', '#94a3b8').attr('font-size', '11');
        gg.selectAll('line').remove();
      });
  }, [data, providers, height]);

  return (
    <div ref={containerRef} className="w-full">
      <svg ref={svgRef} />
      {/* Legend */}
      <div className="flex flex-wrap gap-3 mt-3 justify-center">
        {providers.map(p => (
          <div key={p.id} className="flex items-center gap-1.5">
            <div className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: p.color }} />
            <span className="text-xs text-slate-500">{p.name}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
