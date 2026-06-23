import { useEffect, useRef } from 'react';
import * as d3 from 'd3';

interface BarItem {
  label: string;
  value: number;
  color?: string;
}

interface HorizBarChartProps {
  data: BarItem[];
  height?: number;
  formatValue?: (v: number) => string;
}

export function HorizBarChart({ data, height, formatValue }: HorizBarChartProps) {
  const svgRef = useRef<SVGSVGElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const computedHeight = height ?? Math.max(200, data.length * 40 + 40);

  useEffect(() => {
    if (!svgRef.current || !containerRef.current || data.length === 0) return;

    const containerWidth = containerRef.current.clientWidth;
    const margin = { top: 8, right: 80, bottom: 16, left: 120 };
    const w = containerWidth - margin.left - margin.right;
    const h = computedHeight - margin.top - margin.bottom;

    const svg = d3.select(svgRef.current);
    svg.selectAll('*').remove();
    svg.attr('width', containerWidth).attr('height', computedHeight);

    const g = svg.append('g').attr('transform', `translate(${margin.left},${margin.top})`);

    const x = d3.scaleLinear()
      .domain([0, d3.max(data, d => d.value) ?? 0])
      .nice()
      .range([0, w]);

    const y = d3.scaleBand()
      .domain(data.map(d => d.label))
      .range([0, h])
      .padding(0.3);

    // Bars
    g.selectAll('rect')
      .data(data)
      .join('rect')
      .attr('y', d => y(d.label) ?? 0)
      .attr('height', y.bandwidth())
      .attr('x', 0)
      .attr('width', d => x(d.value))
      .attr('fill', d => d.color ?? '#6366f1')
      .attr('rx', 4);

    // Value labels
    g.selectAll('.val')
      .data(data)
      .join('text')
      .attr('class', 'val')
      .attr('x', d => x(d.value) + 6)
      .attr('y', d => (y(d.label) ?? 0) + y.bandwidth() / 2)
      .attr('dy', '0.35em')
      .attr('font-size', '12')
      .attr('fill', '#475569')
      .text(d => formatValue ? formatValue(d.value) : d.value.toFixed(4));

    // Y Axis (labels)
    g.append('g')
      .call(d3.axisLeft(y).tickSize(0))
      .call(gg => {
        gg.select('.domain').remove();
        gg.selectAll('text').attr('fill', '#64748b').attr('font-size', '12').attr('dx', '-6');
      });
  }, [data, computedHeight, formatValue]);

  return (
    <div ref={containerRef} className="w-full">
      <svg ref={svgRef} />
    </div>
  );
}
