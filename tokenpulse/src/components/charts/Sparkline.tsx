import { useEffect, useRef } from 'react';
import * as d3 from 'd3';

interface SparklineProps {
  data: number[];
  color?: string;
  width?: number;
  height?: number;
}

export function Sparkline({ data, color = '#6366f1', width = 100, height = 32 }: SparklineProps) {
  const svgRef = useRef<SVGSVGElement>(null);

  useEffect(() => {
    if (!svgRef.current || data.length === 0) return;

    const svg = d3.select(svgRef.current);
    svg.selectAll('*').remove();
    svg.attr('width', width).attr('height', height);

    const x = d3.scaleLinear().domain([0, data.length - 1]).range([0, width]);
    const y = d3.scaleLinear().domain([0, d3.max(data) ?? 1]).range([height - 2, 2]);

    const line = d3.line<number>()
      .x((_, i) => x(i))
      .y(d => y(d))
      .curve(d3.curveCatmullRom);

    svg.append('path')
      .datum(data)
      .attr('fill', 'none')
      .attr('stroke', color)
      .attr('stroke-width', 2)
      .attr('d', line);
  }, [data, color, width, height]);

  return <svg ref={svgRef} />;
}
