package com.temi.rhythmgame;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.VideoView;

/**
 * Bug Fix #1 - VideoView 50% 채움 문제
 *
 * 기본 VideoView는 영상 원본 비율을 유지하면서 뷰 중앙에 letterbox 형태로 표시됩니다.
 * layout_weight="1" 로 50% 너비를 확보해도 영상이 실제로 그 공간을 채우지 않습니다.
 *
 * 해결: onMeasure 를 재정의해서 부모 컨테이너가 할당한 크기(50% 너비 x 전체 높이)를
 *       그대로 사용하도록 강제합니다.
 */
public class FillVideoView extends VideoView {

    public FillVideoView(Context context) {
        super(context);
    }

    public FillVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FillVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * 부모가 지정한 크기를 그대로 적용 → 영상이 항상 뷰 전체를 채웁니다.
     * (원본 비율은 무시되지만, 게임 화면에서는 꽉 채우는 것이 더 중요합니다.)
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width  = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
    }
}
