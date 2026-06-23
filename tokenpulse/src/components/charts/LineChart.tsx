import { useEffect, useRef } from 'react';
import * as d3 from 'd3';

interface DataPoint {
  date: string;
  tokens: number;
}

interface LineChartProps {
  data: DataPoint[];
  width?: number;
  height?: number;
}

export function TokenLineChart({ data, height = 200 }: LineChartProps) {
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

    const parseDate = (s: string) => new Date(s + 'T00:00:00Z');

    const x = d3.scaleTime()
      .domain(d3.extent(data, d => parseDate(d.date)) as [Date, Date])
      .range([0, w]);

    const y = d3.scaleLinear()
      .domain([0, d3.max(data, d => d.tokens) ?? 0])
      .nice()
      .range([h, 0]);

    // Grid
    g.append('g')
      .attr('class', 'grid')
      .call(
        d3.axisLeft(y)
          .tickSize(-w)
          .tickFormat(() => '')
          .ticks(5),
      )
      .call(gg => {
        gg.selectAll('line').attr('stroke', '#e2e8f0').attr('stroke-dasharray', '3,3');
        gg.select('.domain').remove();
      });

    // Gradient
    const defs = svg.append('defs');
    const gradient = defs.append('linearGradient')
      .attr('id', 'lineGradient')
      .attr('x1', '0').attr('x2', '0').attr('y1', '0').attr('y2', '1');
    gradient.append('stop').attr('offset', '0%').attr('stop-color', '#6366f1').attr('stop-opacity', 0.3);
    gradient.append('stop').attr('offset', '100%').attr('stop-color', '#6366f1').attr('stop-opacity', 0);

    // Area
    const area = d3.area<DataPoint>()
      .x(d => x(parseDate(d.date)))
      .y0(h)
      .y1(d => y(d.tokens))
      .curve(d3.curveCatmullRom);

    g.append('path')
      .datum(data)
      .attr('fill', 'url(#lineGradient)')
      .attr('d', area);

    // Line
    const line = d3.line<DataPoint>()
      .x(d => x(parseDate(d.date)))
      .y(d => y(d.tokens))
      .curve(d3.curveCatmullRom);

    g.append('path')
      .datum(data)
      .attr('fill', 'none')
      .attr('stroke', '#6366f1')
      .attr('stroke-width', 2.5)
      .attr('d', line);

    // X Axis
    g.append('g')
      .attr('transform', `translate(0,${h})`)
      .call(
        d3.axisBottom(x)
          .ticks(d3.timeDay.every(5))
          .tickFormat(d => d3.timeFormat('%b %d')(d as Date)),
      )
      .call(gg => {
        gg.select('.domain').attr('stroke', '#cbd5e1');
        gg.selectAll('text').attr('fill', '#94a3b8').attr('font-size', '11');
        gg.selectAll('line').attr('stroke', '#cbd5e1');
      });

    // Y Axis
    g.append('g')
      .call(
        d3.axisLeft(y)
          .ticks(5)
          .tickFormat(v => {
            const n = v as number;
            if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
            if (n >= 1_000) return `${(n / 1_000).toFixed(0)}K`;
            return n.toString();
          }),
      )
      .call(gg => {
        gg.select('.domain').remove();
        gg.selectAll('text').attr('fill', '#94a3b8').attr('font-size', '11');
        gg.selectAll('line').remove();
      });
  }, [data, height]);

  return (
    <div ref={containerRef} className="w-full">
      <svg ref={svgRef} />
    </div>
  );
}
