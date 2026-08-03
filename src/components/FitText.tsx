"use client";

import { useLayoutEffect, useRef, useState } from "react";

type Props = {
  text: string;
  /** 최소/최대 폰트 크기(px) */
  min?: number;
  max?: number;
  className?: string;
};

/**
 * 부모 컨테이너 안에 텍스트가 꽉 차도록 폰트 크기를 이진 탐색으로 맞춘다.
 * 다짐 본문이 길이에 따라 자동으로 커지고 작아져 한 화면에 들어가게 한다.
 */
export default function FitText({ text, min = 20, max = 160, className = "" }: Props) {
  const boxRef = useRef<HTMLDivElement>(null);
  const textRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState<number>(max);

  useLayoutEffect(() => {
    const box = boxRef.current;
    const el = textRef.current;
    if (!box || !el) return;

    let lo = min;
    let hi = max;
    let best = min;

    // 이진 탐색: 컨테이너를 넘지 않는 최대 크기 찾기
    for (let i = 0; i < 12 && lo <= hi; i++) {
      const mid = Math.floor((lo + hi) / 2);
      el.style.fontSize = `${mid}px`;
      const fits = el.scrollHeight <= box.clientHeight && el.scrollWidth <= box.clientWidth;
      if (fits) {
        best = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    el.style.fontSize = `${best}px`;
    setSize(best);
  }, [text, min, max]);

  return (
    <div
      ref={boxRef}
      className="absolute inset-0 flex items-center justify-center overflow-hidden"
    >
      <div
        ref={textRef}
        className={className}
        style={{ fontSize: size, lineHeight: 1.35 }}
      >
        {text}
      </div>
    </div>
  );
}
