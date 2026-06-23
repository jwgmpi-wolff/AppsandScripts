import { useEffect, useRef } from 'react';
import * as d3 from 'd3';

interface DonutSlice {
  label: string;
  value: number;
  color: string;
}

interface DonutChartProps {
  data: DonutSlice[];
  size?: number;
}

export function DonutChart({ data, size = 200 }: DonutChartProps) {
  const svgRef = useRef<SVGSVGElement>(null);

  useEffect(() => {
    if (!svgRef.current || data.length === 0) return;

    const svg = d3.select(svgRef.current);
    svg.selectAll('*').remove();

    const radius = size / 2;
    const innerRadius = radius * 0.6;

    svg.attr('width', size).attr('height', size);

    const g = svg.append('g').attr('transform', `translate(${radius},${radius})`);

    const pie = d3.pie<DonutSlice>()
      .value(d => d.value)
      .sort(null)
      .padAngle(0.025);

    const arc = d3.arc<d3.PieArcDatum<DonutSlice>>()
      .innerRadius(innerRadius)
      .outerRadius(radius - 4)
      .cornerRadius(4);

    const arcs = pie(data);

    g.selectAll('path')
      .data(arcs)
      .join('path')
      .attr('fill', d => d.data.color)
      .attr('d', arc);

    // Center text
    const total = d3.sum(data, d => d.value);
    g.append('text')
      .attr('text-anchor', 'middle')
      .attr('dy', '-0.2em')
      .attr('font-size', '14')
      .attr('font-weight', '700')
      .attr('fill', '#1e293b')
      .text(`$${total.toFixed(0)}`);
    g.append('text')
      .attr('text-anchor', 'middle')
      .attr('dy', '1.2em')
      .attr('font-size', '11')
      .attr('fill', '#94a3b8')
      .text('total');
  }, [data, size]);

  return (
    <div className="flex flex-col items-center gap-4">
      <svg ref={svgRef} />
      <div className="flex flex-col gap-1.5 w-full">
        {data.map(d => (
          <div key={d.label} className="flex items-center justify-between text-xs">
            <div className="flex items-center gap-1.5">
              <div className="w-2.5 h-2.5 rounded-full flex-shrink-0" style={{ backgroundColor: d.color }} />
              <span className="text-slate-600 truncate max-w-[120px]">{d.label}</span>
            </div>
            <span className="font-medium text-slate-700">${d.value.toFixed(2)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
