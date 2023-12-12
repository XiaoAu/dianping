package com.hmdp.utils;

import java.util.Calendar;

public class EndTime {
    public static void main(String[] args) {
        System.out.println(getMillisecondsUntilEndOfDay());
    }

    //获取距今天24点结束的毫秒值
    public static long getMillisecondsUntilEndOfDay() {
        //获取当前时间的毫秒值
        long time = System.currentTimeMillis();

        //获取今天结束的毫秒值
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 23); //24小时制
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        long endTime =  calendar.getTimeInMillis();

        //今天24点结束的毫秒值减去当前时间的毫秒值，加1才是24点整，因为上面是999毫秒
        return endTime - time + 1;

    }
}
