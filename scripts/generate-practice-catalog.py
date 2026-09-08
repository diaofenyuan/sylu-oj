"""生成扩展题库及确定性判题数据：python scripts/generate-practice-catalog.py。

参考算法只在离线生成时运行，不向学生接口分发；修改题目后需同步生成资源。
"""
import bisect
import collections
import functools
import heapq
import itertools
import json
import math
import pathlib
import random

ROOT = pathlib.Path(__file__).resolve().parents[1]
QUESTIONS = []
LEVEL = "EASY"
MOD = 1_000_000_007


def ints(s):
    return list(map(int, s.split()))


def line(xs):
    return " ".join(map(str, xs))


def array(xs):
    return str(len(xs)) + "\n" + line(xs)


def yn(value):
    return "YES" if value else "NO"


def digit_sum(n,b):
    total=0
    while n:
        total+=n%b
        n//=b
    return total


def add(title, topic, statement, input_format, output_format, solve, cases):
    if input_format.startswith("两行"):
        cases=[data.replace(" ","\n") for data in cases]
    assert len(set(cases)) >= 5, title
    index = 26 + sum(q["difficulty"] == LEVEL for q in QUESTIONS)
    QUESTIONS.append(dict(difficulty=LEVEL, title=title, topic=topic,
                          description=f"## 题目描述\n{statement}\n\n## 输入格式\n{input_format}\n\n## 输出格式\n{output_format}\n\n知识点：{topic}。整数结果可能超过 32 位，请选择合适的整数类型。",
                          code=f"PRACTICE-{LEVEL}-{index:02d}", _solve=solve, _cases=cases))


def scalar(title, topic, statement, fmt, solve, cases, out="输出一行一个整数。"):
    add(title, topic, statement, fmt, out, lambda s: solve(*ints(s)), list(map(str, cases)))


def seq(title, topic, statement, solve, cases=None, out="输出一行一个整数。", bound="1 ≤ n ≤ 200000，|a_i| ≤ 10^9"):
    if cases is None:
        cases = [[3, -1, 3, 0, 7], [0], [-5, -2, -9], [4, 4, 4, 4], list(range(1000))]
    add(title, topic, statement, f"第一行 n，第二行 n 个整数（{bound}）。", out,
        lambda s: solve(ints(s)[1:]), [array(a) for a in cases])


# 入门：每道题有独立的计算目标和明确的边界约定。
scalar("整数的绝对值", "分支", "给定整数 x，求 |x|。", "一行 x，-10^12 ≤ x ≤ 10^12。", abs, [-12, 0, 1, -10**12, 10**12])
scalar("两点的曼哈顿距离", "坐标", "求 (x1,y1) 到 (x2,y2) 的曼哈顿距离 |x1-x2|+|y1-y2|。", "一行四个整数，绝对值均不超过 10^9。", lambda a,b,c,d: abs(a-c)+abs(b-d), ["1 2 4 6", "0 0 0 0", "-1 -2 1 2", "1000000000 0 -1000000000 0", "4 5 4 -6"])
scalar("整批装箱", "向上取整", "有 n 件物品，每箱最多装 k 件，求装完所有物品至少需要多少箱。", "一行 n k，0 ≤ n ≤ 10^12，1 ≤ k ≤ 10^9。", lambda n,k:(n+k-1)//k, ["10 3", "0 2", "1 8", "1000000000000 1", "12 4"])
scalar("等差数列求和", "等差数列", "首项 a、公差 d、项数 n 的等差数列，求全部项之和。", "一行 a d n，|a|,|d| ≤ 10000，1 ≤ n ≤ 100000。", lambda a,d,n:n*(2*a+(n-1)*d)//2, ["2 3 4", "0 0 1", "10 -2 6", "-4 3 8", "10000 10000 100000"])
scalar("区间整数数量", "分支", "求闭区间 [l,r] 内整数的数量；l>r 时视为空区间。", "一行 l r，|l|,|r| ≤ 10^12。", lambda l,r:max(0,r-l+1), ["2 6", "4 4", "5 2", "-5 3", "-1000000000000 1000000000000"])
scalar("温度整数换算", "整数运算", "摄氏温度 C 转为华氏温度 F=9C/5+32，结果向下取整。", "一行整数 C，-1000 ≤ C ≤ 1000。", lambda c:9*c//5+32, [25,0,-1,-1000,1000])
scalar("钟面时针", "取模", "24 小时制的小时 h 对应 12 小时钟面的哪个数字？余数为 0 时输出 12。", "一行 h，0 ≤ h ≤ 23。", lambda h:h%12 or 12, [13,0,12,23,7])
scalar("跨午夜用时", "时间计算", "已知起止时刻，结束在开始之后的 24 小时内。两时刻相同表示用时 0。求相隔分钟数。", "一行 h1 m1 h2 m2，小时 0..23，分钟 0..59。", lambda h,m,H,M:(H*60+M-h*60-m)%1440, ["23 50 0 10", "0 0 0 0", "12 30 13 0", "0 1 0 0", "8 0 7 59"])
scalar("月份天数", "日历", "按公历求给定年月的天数。闰年能被 400 整除，或能被 4 整除但不能被 100 整除。", "一行 y m，1 ≤ y ≤ 9999，1 ≤ m ≤ 12。", lambda y,m:([31,29 if y%400==0 or y%4==0 and y%100!=0 else 28,31,30,31,30,31,31,30,31,30,31][m-1]), ["2024 2", "1900 2", "2000 2", "2025 4", "1 12"])
scalar("成绩等级", "分支", "成绩 90..100 为 A，80..89 为 B，70..79 为 C，60..69 为 D，0..59 为 E。", "一行整数 s，0 ≤ s ≤ 100。", lambda s:"A" if s>=90 else "B" if s>=80 else "C" if s>=70 else "D" if s>=60 else "E", [95,0,59,60,70,80,90,100], "输出对应的大写字母。")
scalar("三数中间值", "排序", "给出三个整数，输出排序后中间的数，重复值也占一个位置。", "一行三个整数，绝对值不超过 10^9。", lambda *a:sorted(a)[1], ["9 1 4", "2 2 1", "-3 -1 -2", "0 0 0", "1000000000 -1000000000 1"])
scalar("区间倍数计数", "整除", "求闭区间 [l,r] 中能被 k 整除的整数个数，0 也算 k 的倍数。", "一行 l r k，0 ≤ l ≤ r ≤ 10^12，1 ≤ k ≤ 10^9。", lambda l,r,k:r//k-(l-1)//k, ["3 20 4", "0 0 3", "7 7 7", "1 2 9", "0 1000000000000 1"])
scalar("平方和", "求和公式", "求 1²+2²+…+n²，n=0 时为 0。", "一行 n，0 ≤ n ≤ 1000000。", lambda n:n*(n+1)*(2*n+1)//6, [3,0,1,100,1000000])
scalar("交错求和", "规律", "求 1-2+3-4+…+(-1)^(n+1)n。", "一行 n，1 ≤ n ≤ 10^12。", lambda n:(n+1)//2 if n%2 else -n//2, [5,1,2,999999999999,1000000000000])
scalar("数字位数", "数位", "求非负整数 n 的十进制位数，0 的位数为 1。", "一行 n，0 ≤ n ≤ 10^18。", lambda n:len(str(n)), [12345,0,9,10,10**18])
scalar("数字乘积", "数位", "求非负整数 n 所有十进制数字的乘积，0 的答案为 0。", "一行 n，0 ≤ n ≤ 10^18。", lambda n:math.prod(map(int,str(n))), [234,0,105,999999999999999999,1])
scalar("末尾两位", "取模", "输出 n 的十进制末尾两位，不足两位补前导零。", "一行 n，0 ≤ n ≤ 10^18。", lambda n:f"{n%100:02d}", [1234,0,7,100,10**18], "输出恰好两个数字字符。")
scalar("整数回文", "数位", "判断 n 的十进制表示是否正反相同，不添加前导零。", "一行 n，0 ≤ n ≤ 10^18。", lambda n:yn(str(n)==str(n)[::-1]), [12321,0,10,1111,123456], "是回文输出 YES，否则 NO。")
scalar("整数开平方", "二分", "求最大的非负整数 k，使 k² ≤ n。", "一行 n，0 ≤ n ≤ 10^18。", math.isqrt, [20,0,1,10**18,999999999999999999])
scalar("三角形种类", "排序", "三边不能构成三角形输出 INVALID；否则按角分为 RIGHT、ACUTE、OBTUSE（直角、锐角、钝角）。", "一行三个正整数，均不超过 10^9。", lambda *a:(lambda x:"INVALID" if x[0]+x[1]<=x[2] else "RIGHT" if x[0]**2+x[1]**2==x[2]**2 else "ACUTE" if x[0]**2+x[1]**2>x[2]**2 else "OBTUSE")(sorted(a)), ["3 4 5", "1 2 3", "4 4 4", "2 3 4", "1000000000 1000000000 1000000000"], "输出对应英文单词。")
scalar("棋盘格颜色", "奇偶", "棋盘 (1,1) 为黑色，相邻格颜色交替。判断 (r,c) 的颜色。", "一行 r c，1 ≤ r,c ≤ 10^9。", lambda r,c:"BLACK" if (r+c)%2==0 else "WHITE", ["1 1", "1 2", "2 2", "1000000000 1", "999999999 999999999"], "输出 BLACK 或 WHITE。")
scalar("分段停车费", "分支", "停车 0 分钟免费；1..60 分钟 5 元；超出部分每开始 30 分钟加收 2 元。求费用。", "一行分钟数 t，0 ≤ t ≤ 10^9。", lambda t:0 if t==0 else 5+max(0,(t-60+29)//30)*2, [75,0,60,61,90,91])
scalar("最后一页", "除法", "书有 n 条记录，每页容纳 k 条，求最后一页的记录数。", "一行 n k，1 ≤ n,k ≤ 10^12。", lambda n,k:(n-1)%k+1, ["23 10", "20 10", "1 100", "100 1", "1000000000000 999999999999"])
scalar("累积翻倍", "循环", "初始有 a 个细胞，每轮翻倍，至少多少轮后不少于 b 个？", "一行 a b，1 ≤ a,b ≤ 10^18。", lambda a,b:max(0,((b+a-1)//a-1).bit_length()), ["3 20", "5 5", "9 1", "1 1000000000000000000", "2 8"])
scalar("奇数区间和", "求和", "求闭区间 [l,r] 内全部奇数之和。", "一行 l r，0 ≤ l ≤ r ≤ 10^9。", lambda l,r:((r+1)//2)**2-(l//2)**2, ["2 9", "0 0", "3 3", "4 4", "0 1000000000"])
seq("零元素计数", "数组", "统计数组中等于 0 的元素个数。", lambda a:a.count(0))
seq("奇数元素求和", "数组", "求数组中所有奇数元素之和，负奇数也参与求和。", lambda a:sum(x for x in a if x%2))
seq("相邻差的绝对值之和", "数组", "求所有相邻元素差的绝对值之和；n=1 时为 0。", lambda a:sum(abs(x-y) for x,y in zip(a,a[1:])))
seq("元素极差", "数组", "求数组最大值减最小值。", lambda a:max(a)-min(a))
seq("最小值出现次数", "数组", "统计数组最小值出现多少次。", lambda a:a.count(min(a)))
seq("非递减检查", "数组", "判断每个元素是否都不大于它后面的元素。", lambda a:yn(all(x<=y for x,y in zip(a,a[1:]))), out="满足输出 YES，否则 NO。")
seq("局部峰值", "数组", "统计严格大于左右相邻元素的内部元素个数。首尾不计；n<3 时为 0。", lambda a:sum(a[i]>a[i-1] and a[i]>a[i+1] for i in range(1,len(a)-1)))
seq("偶数位置求和", "下标", "下标从 1 开始，求第 2、4、6、… 个元素之和。", lambda a:sum(a[1::2]))
seq("前缀最大值", "数组", "第 i 个输出为前 i 个输入元素的最大值。", lambda a:line(itertools.accumulate(a,max)), out="一行 n 个整数，空格分隔。")
seq("相邻相等对", "数组", "统计满足 a_i=a_(i+1) 的下标 i 的数量。", lambda a:sum(x==y for x,y in zip(a,a[1:])) )
seq("稳定移动零", "数组", "将全部 0 移到数组末尾，保持非零元素的相对次序。", lambda a:line([x for x in a if x]+[0]*a.count(0)), out="一行 n 个整数，空格分隔。")
seq("正负交替次数", "数组", "统计相邻元素一正一负的对数，含 0 的一对不计。", lambda a:sum(x*y<0 for x,y in zip(a,a[1:])) )
seq("总和的符号", "数组", "计算数组总和，正数输出 POSITIVE，负数输出 NEGATIVE，零输出 ZERO。", lambda a:"POSITIVE" if sum(a)>0 else "NEGATIVE" if sum(a)<0 else "ZERO", out="输出一个英文单词。")
seq("奇偶分组", "稳定分组", "先输出所有偶数，再输出所有奇数，两组内保持输入顺序。", lambda a:line([x for x in a if x%2==0]+[x for x in a if x%2]), out="一行 n 个整数，空格分隔。")
seq("相邻元素交换", "数组", "交换第 1、2 项，第 3、4 项，依此类推。奇数长度的末项不变。", lambda a:line([a[i+1] if i%2==0 and i+1<len(a) else a[i-1] if i%2 else a[i] for i in range(len(a))]), out="一行 n 个整数，空格分隔。")


def stringq(title, topic, statement, solve, cases=None, out="输出一行处理结果。", fmt="一行非空字符串 s，只含英文字母，长度不超过 200000。"):
    cases = cases or ["aBba", "a", "XYZ", "aaaa", "algorithm"]
    add(title,topic,statement,fmt,out,solve,cases)


stringq("切换字母大小写", "字符", "将每个小写英文字母转为大写，大写转为小写。", str.swapcase)
stringq("首尾字符", "字符", "输出字符串的第一个和最后一个字符，中间用空格分隔。单字符时输出两次。", lambda s:s[0]+" "+s[-1])
stringq("连续字符压缩", "字符串", "把每段连续相同字符压缩成一个字符，大小写不同视为不同字符。", lambda s:"".join(k for k,g in itertools.groupby(s)))
stringq("大写字母统计", "字符", "统计字符串中大写英文字母的个数。", lambda s:sum(c.isupper() for c in s))
stringq("字母表序号", "字符", "输出每个字母在字母表中的序号，忽略大小写，a 为 1，z 为 26。", lambda s:line(ord(c.lower())-96 for c in s))
stringq("移除元音", "字符串", "删除所有元音 aeiou（忽略大小写）。若删完为空，输出 EMPTY。", lambda s:"".join(c for c in s if c.lower() not in "aeiou") or "EMPTY")
stringq("字典序较小串", "字符串", "给定两串小写字母，输出字典序较小的串；相等时输出任意一串。", lambda s:min(s.split()), ["cat dog","a aa","z a","same same","apple apply"], fmt="两行非空小写字符串，各长 1..200000。")
stringq("二进制取反", "字符串", "将二进制字符串的 0 与 1 互换，保留长度与前导零。", lambda s:s.translate(str.maketrans("01","10")), ["1010","0","1","00000","1110010"], fmt="一行仅含 0 和 1 的串，长度 1..200000。")
stringq("循环字母后继", "字符", "每个小写字母替换为字母表中的下一个字母，z 的后继是 a。", lambda s:"".join(chr((ord(c)-96)%26+97) for c in s), ["xyz","z","a","zzzz","algorithm"], fmt="一行小写字母串，长度 1..200000。")
stringq("字符种类统计", "字符", "输出大写字母、小写字母和数字字符的数量，按此顺序。", lambda s:line([sum(c.isupper() for c in s),sum(c.islower() for c in s),sum(c.isdigit() for c in s)]), ["AbC123x","0","Z","abcde","A1b2C3"], fmt="一行仅含英文字母与数字的串，长度 1..200000。")

LEVEL = "BASIC"
POS = [[3,1,3,2,7],[1],[5,5,5],[9,7,5,3,1],list(range(1,101))]
seq("第二大的不同值", "排序", "输出第二大的不同数值，不存在时输出 NONE。", lambda a:sorted(set(a))[-2] if len(set(a))>1 else "NONE")
seq("众数与频次", "计数", "输出出现最多的数及次数；次数相同时选数值较小者。", lambda a:(lambda c:line(min(c.items(),key=lambda p:(-p[1],p[0]))))(collections.Counter(a)), out="一行两个整数：数值 次数。")
seq("保持顺序去重", "集合", "每个不同数值只保留第一次出现，保持原来的顺序。", lambda a:line(dict.fromkeys(a)), out="一行若干整数，空格分隔。")
seq("坐标离散化", "排序", "将每个数替换为它在所有不同数值升序排列中的排名，排名从 1 开始。", lambda a:(lambda d:line(d[x] for x in a))({x:i+1 for i,x in enumerate(sorted(set(a)))}), out="一行 n 个排名。")
seq("最小相邻排序差", "排序", "排序后求相邻两项差的最小值，只有一个数时输出 0。", lambda a:min((y-x for x,y in zip(sorted(a),sorted(a)[1:])),default=0))
seq("数组平衡位置", "前缀和", "求最小下标 i，使 i 左侧的元素和等于右侧的元素和，不包括 a_i；无解输出 -1。", lambda a:next((i+1 for i,p in enumerate(itertools.accumulate(a)) if 2*p-a[i]==sum(a)),-1))
seq("最长连续零段", "扫描", "求连续 0 组成的最长段长度，没有 0 时输出 0。", lambda a:max((len(list(g)) for k,g in itertools.groupby(a) if k==0),default=0))
seq("绝对值排序", "自定义排序", "按绝对值升序排序，绝对值相等时按数值升序。", lambda a:line(sorted(a,key=lambda x:(abs(x),x))), out="一行 n 个整数。")
seq("最少统一步数", "中位数", "每步将任意一个元素加 1 或减 1。求使全部元素相等的最少步数。", lambda a:sum(abs(x-sorted(a)[len(a)//2]) for x in a))
seq("非递减修补", "贪心", "每步可将任意一个元素加 1。求使数组非递减的最少步数。", lambda a:sum(m-x for x,m in zip(a,itertools.accumulate(a,max)) ))
seq("数组所有数的公约数", "欧几里得", "求所有正整数的最大公约数。", lambda a:functools.reduce(math.gcd,a),POS,bound="1 ≤ n ≤ 200000，1 ≤ a_i ≤ 10^9")
seq("多数元素验证", "计数", "若某数出现次数严格超过 n/2，输出该数，否则输出 NONE。", lambda a:next((x for x,c in collections.Counter(a).items() if c*2>len(a)),"NONE"))
seq("删除后剩余不同值", "计数", "必须删除一个元素，求剩余数组不同值个数的最大值。", lambda a:len(set(a))-(len(set(a))==len(a)))
seq("配对手套", "计数", "每个整数代表一种颜色，两只同色手套配成一对。求最多能配多少对，每只只能使用一次。", lambda a:sum(c//2 for c in collections.Counter(a).values()))
seq("相等元素对", "组合计数", "统计满足 i<j 且 a_i=a_j 的下标对数量。", lambda a:sum(c*(c-1)//2 for c in collections.Counter(a).values()))


def params_array(title, topic, statement, solve, cases, fmt="第一行 n k，第二行 n 个整数；1 ≤ n ≤ 200000，|a_i|,|k| ≤ 10^9。", out="输出一行一个整数。"):
    add(title,topic,statement,fmt,out,lambda s:solve(ints(s)[1],ints(s)[2:]),[f"{len(a)} {k}\n{line(a)}" for k,a in cases])


params_array("目标值出现区间", "二分", "数组已非递减排序，输出 k 首次与末次出现的位置（从 1 开始）；不存在输出 -1 -1。", lambda k,a:line([bisect.bisect_left(a,k)+1,bisect.bisect_right(a,k)]) if k in a else "-1 -1", [(2,[1,2,2,3]),(1,[1]),(5,[1,2]),(-1,[-1,-1,-1]),(9,list(range(20)))],out="一行两个整数。")
params_array("最接近查询值", "二分", "输出与 k 的绝对差最小的元素，距离相等时选较小元素。",lambda k,a:min(a,key=lambda x:(abs(x-k),x)), [(4,[1,3,5,8]),(0,[9]),(-5,[-9,-1]),(7,[7,7]),(100,list(range(20)))])
params_array("固定长度区间最大和", "滑动窗口", "求长度恰好为 k 的连续子数组的最大和。",lambda k,a:max(sum(a[i:i+k]) for i in range(len(a)-k+1)),[(2,[1,-2,3,4]),(1,[-5]),(3,[-4,-2,-8]),(1,[2,2,2]),(30,list(range(100)))],fmt="第一行 n k，第二行 n 个整数，1 ≤ k ≤ n ≤ 200000，|a_i| ≤ 10^9。")
params_array("旋转数组查询", "下标取模", "把数组循环右移 k 次后输出全部元素；每次把末项移到开头。",lambda k,a:line(a[-(k%len(a)):]+a[:-(k%len(a))]) if k%len(a) else line(a),[(2,[1,2,3,4,5]),(0,[7]),(8,[1,2]),(1,[-3,0]),(10**9,list(range(9)))],fmt="第一行 n k，第二行 n 个整数，1 ≤ n ≤ 200000，0 ≤ k ≤ 10^9，|a_i| ≤ 10^9。",out="一行 n 个整数。")
params_array("不超过预算的商品", "排序贪心", "每件商品最多买一次，价格为正整数。在总价不超过 k 的前提下最多购买多少件？",lambda k,a:sum(v<=k for v in itertools.accumulate(sorted(a))),[(7,[4,2,3,1]),(0,[1]),(9,[10,11]),(6,[2,2,2]),(1000,list(range(1,100)))],fmt="第一行 n k，第二行 n 个价格；1 ≤ n ≤ 200000，0 ≤ k ≤ 10^12，1 ≤ a_i ≤ 10^9。")
params_array("至少达到目标的最少项", "排序贪心", "从非负数组选择尽可能少的元素，使其和至少为 k，无解输出 -1；k=0 可不选。",lambda k,a:0 if k==0 else next((i+1 for i,v in enumerate(itertools.accumulate(sorted(a,reverse=True))) if v>=k),-1),[(8,[1,5,3]),(0,[0]),(3,[1,1]),(5,[5]),(100,list(range(30)))],fmt="第一行 n k，第二行 n 个整数；1 ≤ n ≤ 200000，0 ≤ k ≤ 10^12，0 ≤ a_i ≤ 10^9。")
params_array("数组元素频次查询", "计数", "输出数组中等于 k 的元素出现次数。",lambda k,a:a.count(k),[(2,[1,2,2,3]),(0,[0]),(9,[1,2]),(-1,[-1,-1]),(3,list(range(100)))])
params_array("和为目标的下标对数", "哈希", "统计 i<j 且 a_i+a_j=k 的下标对数，重复值按下标分别计数。",lambda k,a:sum(x+y==k for i,x in enumerate(a) for y in a[i+1:]),[(4,[1,3,2,2]),(0,[0]),(0,[-1,1,0,0]),(6,[3,3,3]),(50,list(range(100)))])
params_array("删除指定元素", "数组", "删除数组中所有等于 k 的元素，保留其他元素顺序；剩余为空时输出 EMPTY。",lambda k,a:line(x for x in a if x!=k) or "EMPTY",[(2,[1,2,3,2]),(1,[1]),(0,[1,2]),(-1,[-1,0]),(3,list(range(10)))],out="一行剩余元素，或 EMPTY。")
params_array("整除筛选", "整除", "输出能被正整数 k 整除的元素，保持输入次序；没有则输出 EMPTY。",lambda k,a:line(x for x in a if x%k==0) or "EMPTY",[(3,[1,3,0,-6]),(1,[7]),(9,[1,2]),(2,[2,2]),(7,list(range(50)))],fmt="第一行 n k，第二行 n 个整数；1 ≤ n ≤ 200000，1 ≤ k ≤ 10^9，|a_i| ≤ 10^9。",out="一行筛选结果，或 EMPTY。")

scalar("正因子数量", "试除", "求 n 的正因子个数，包含 1 和 n。", "一行 n，1 ≤ n ≤ 10^12。",lambda n:sum(1 if d*d==n else 2 for d in range(1,math.isqrt(n)+1) if n%d==0), [12,1,49,99991,10**12])
scalar("正因子之和", "试除", "求 n 所有正因子之和。", "一行 n，1 ≤ n ≤ 10^12。",lambda n:sum(d+(n//d if d*d!=n else 0) for d in range(1,math.isqrt(n)+1) if n%d==0), [12,1,36,99991,10**12])
scalar("完全数判断", "因子", "若 n 等于它所有小于自身的正因子之和，则称完全数。判断 n 是否为完全数。", "一行 n，1 ≤ n ≤ 10^8。",lambda n:yn(n>1 and sum(d+(n//d if d*d!=n else 0) for d in range(1,math.isqrt(n)+1) if n%d==0)==2*n),[6,1,28,12,496],"输出 YES 或 NO。")
scalar("互质判断", "欧几里得", "判断 a,b 的最大公约数是否为 1。", "一行 a b，1 ≤ a,b ≤ 10^18。",lambda a,b:yn(math.gcd(a,b)==1),["8 15","1 1","6 9","1000000000000000000 999999999999999999","49 77"],"输出 YES 或 NO。")
scalar("约分分数", "欧几里得", "将 a/b 约成最简分数，0 的结果为 0/1。", "一行 a b，0 ≤ a ≤ 10^12，1 ≤ b ≤ 10^12。",lambda a,b:f"{a//math.gcd(a,b)}/{b//math.gcd(a,b)}",["12 18","0 9","7 1","1000000000000 1000000000000","17 19"],"输出 分子/分母，不含空格。")
scalar("组合数精确值", "组合", "求从 n 个不同元素中选 k 个的方案数。", "一行 n k，0 ≤ k ≤ n ≤ 60。",math.comb,["5 2","0 0","60 30","10 0","10 10"])
scalar("二进制末尾零", "位运算", "求正整数 n 的二进制表示末尾连续 0 的个数。", "一行 n，1 ≤ n ≤ 10^18。",lambda n:(n&-n).bit_length()-1,[12,1,1024,10**18,999999999999999999])
scalar("最低有效位", "位运算", "输出 n 二进制最低位的 1 对应的数值；n=0 时输出 0。", "一行 n，0 ≤ n ≤ 10^18。",lambda n:n&-n,[12,0,1,1024,10**18])
scalar("汉明距离", "位运算", "求两个非负整数的二进制表示中不同位的数量，短的高位补 0。", "一行 a b，0 ≤ a,b < 2^60。",lambda a,b:(a^b).bit_count(),["1 4","0 0","7 0","1024 1023","1152921504606846975 0"])
scalar("不小于 n 的二次幂", "位运算", "求不小于 n 的最小 2 的非负整数次幂。", "一行 n，1 ≤ n ≤ 10^18。",lambda n:1<<(n-1).bit_length(),[9,1,2,1024,10**18])
scalar("进制数字和", "进制", "求非负整数 n 在 b 进制下所有数位的数值之和。", "一行 n b，0 ≤ n ≤ 10^18，2 ≤ b ≤ 36。",lambda n,b:digit_sum(n,b),["31 16","0 2","123 10","255 2","1000000000000000000 36"])


stringq("异位词判断", "字符计数", "判断两个小写字符串能否通过重排字符变得相同。",lambda s:yn(collections.Counter(s.split()[0])==collections.Counter(s.split()[1])),["listen silent","a a","ab aa","abc abcd","aabb abab"],fmt="两行非空小写字符串，各长 1..200000。",out="输出 YES 或 NO。")
stringq("第一个唯一字符", "计数", "输出第一个只出现一次的字符的位置（从 1 开始），不存在输出 -1。",lambda s:next((i+1 for i,c in enumerate(s) if s.count(c)==1),-1))
stringq("游程编码", "字符串", "将每段相同字符编码为字符紧接十进制长度，如 aaabb 输出 a3b2。",lambda s:"".join(k+str(len(list(g))) for k,g in itertools.groupby(s)))
stringq("单词顺序翻转", "字符串", "将单词顺序逆转，单词内部不变。忽略首尾和多余空格，输出单词间一个空格。",lambda s:" ".join(s.split()[::-1]),["one two three","a","  hello   world ","x x x","algorithm and data structures"],fmt="一行由英文字母和空格组成的字符串，长 1..200000，至少一个单词。")
stringq("最长单词", "字符串", "输出最长单词，长度相同取最先出现的。",lambda s:max(s.split(),key=len),["one two three","a","hello world","x yy zzz","algorithm and data structures"],fmt="一行英文单词，单词间一个空格，总长 1..200000。")
stringq("公共前缀", "字符串", "求两个小写字符串的最长公共前缀；为空输出 EMPTY。",lambda s:(lambda a,b:a[:next((i for i,(x,y) in enumerate(zip(a,b)) if x!=y),min(len(a),len(b)))])(*s.split()) or "EMPTY",["flower flow","a b","abc abc","prefix pre","abc abcd"],fmt="两行非空小写字符串，各长 1..200000。")
stringq("子序列检查", "双指针", "判断第一串能否通过删除第二串的若干字符得到，允许不删除。",lambda s:(lambda a,b:(lambda it:yn(all(c in it for c in a)))(iter(b)))(*s.split()),["ace abcde","a a","aa a","axc abc","abc aabbcc"],fmt="两行非空小写字符串，各长 1..200000。",out="输出 YES 或 NO。")
stringq("循环同构字符串", "字符串", "判断两个字符串是否可通过循环旋转互相得到，允许旋转零次。",lambda s:(lambda a,b:yn(len(a)==len(b) and b in a+a))(*s.split()),["abcd cdab","a a","ab aa","abc ab","abab baba"],fmt="两行非空小写字符串，各长 1..200000。",out="输出 YES 或 NO。")
stringq("最少回文替换", "双指针", "每次替换一个字符为任意字母，至少替换多少次可使字符串成为回文？",lambda s:sum(s[i]!=s[-i-1] for i in range(len(s)//2)))
stringq("二进制转十进制", "进制", "将给定二进制串转换为十进制整数，可包含前导零。",lambda s:int(s,2),["1011","0","1","000010", "1"*60],fmt="一行二进制串，长度 1..60。")


def matrixq(title,topic,statement,solve,cases=None,out="输出一行一个整数。"):
    cases=cases or [[[1,2,3],[4,5,6]],[[0]],[[-1,-2],[-3,-4]],[[2,2,2,2]],[[i*10+j for j in range(10)] for i in range(10)]]
    add(title,topic,statement,"第一行 n m，随后 n 行各 m 个整数，1 ≤ n,m ≤ 300，|a_ij| ≤ 10000。",out,
        lambda s:solve((lambda z:[z[2+i*z[1]:2+(i+1)*z[1]] for i in range(z[0])])(ints(s))),[f"{len(a)} {len(a[0])}\n"+"\n".join(map(line,a)) for a in cases])


matrixq("矩阵行和", "矩阵", "分别求每一行的元素之和，按行序输出。",lambda a:line(map(sum,a)),out="一行 n 个整数。")
matrixq("矩阵列最大值", "矩阵", "分别求每一列的最大值。",lambda a:line(map(max,zip(*a))),out="一行 m 个整数。")
matrixq("矩阵顺时针旋转", "矩阵", "将矩阵顺时针旋转 90 度，得到 m 行 n 列矩阵。",lambda a:"\n".join(map(line,zip(*a[::-1]))),out="输出 m 行，每行 n 个整数。")
matrixq("矩阵边框和", "矩阵", "求位于矩阵边框上的元素之和，每个位置只计算一次。",lambda a:sum(x for i,row in enumerate(a) for j,x in enumerate(row) if i in (0,len(a)-1) or j in (0,len(row)-1)))

LEVEL = "INTERMEDIATE"


def subarrays(a):
    return (a[i:j] for i in range(len(a)) for j in range(i+1,len(a)+1))


def max_product(a):
    hi=lo=best=a[0]
    for x in a[1:]:
        hi,lo=max(x,hi*x,lo*x),min(x,hi*x,lo*x)
        best=max(best,hi)
    return best


params_array("和至少为目标的最短区间", "双指针", "所有元素为正，求和至少为 k 的连续子数组的最短长度；无解输出 0。",lambda k,a:min((len(b) for b in subarrays(a) if sum(b)>=k),default=0),[(7,[2,3,1,2,4,3]),(1,[1]),(8,[2,3]),(5,[5,5]),(90,list(range(1,30)))],fmt="第一行 n k，第二行 n 个正整数，1 ≤ n ≤ 200000，1 ≤ k ≤ 10^12，a_i ≤ 10^9。")
params_array("和等于目标的连续区间数", "前缀和与哈希", "统计和等于 k 的非空连续子数组个数。",lambda k,a:sum(sum(b)==k for b in subarrays(a)),[(3,[1,2,1,2]),(0,[0]),(0,[-1,1,0]),(4,[2,2,2]),(10,list(range(-10,15)))])
params_array("和能整除 k 的区间数", "前缀余数", "统计元素和能被 k 整除的非空连续子数组个数，负数之和也参与判断。",lambda k,a:sum(sum(b)%k==0 for b in subarrays(a)),[(5,[4,5,0,-2,-3,1]),(1,[0]),(3,[-1,1]),(2,[2,2,2]),(7,list(range(-10,15)))],fmt="第一行 n k，第二行 n 个整数，1 ≤ n ≤ 200000，1 ≤ k ≤ 10^9，|a_i| ≤ 10^9。")
params_array("恰好 k 个不同值", "滑动窗口", "统计恰好包含 k 个不同数值的非空连续子数组的数量。",lambda k,a:sum(len(set(b))==k for b in subarrays(a)),[(2,[1,2,1,2,3]),(1,[9]),(3,[1,2]),(1,[2,2,2]),(5,list(range(20)))],fmt="第一行 n k，第二行 n 个整数，1 ≤ n,k ≤ 200000，|a_i| ≤ 10^9。")
params_array("至多 k 种值的最长区间", "滑动窗口", "求最多包含 k 个不同数值的连续子数组的最大长度。",lambda k,a:max((len(b) for b in subarrays(a) if len(set(b))<=k),default=0),[(2,[1,2,1,3,3]),(1,[9]),(1,[1,2]),(1,[2,2,2]),(5,list(range(20)))],fmt="第一行 n k，第二行 n 个整数，1 ≤ k ≤ n ≤ 200000，|a_i| ≤ 10^9。")
params_array("长度至少 k 的最大区间和", "前缀最小值", "求长度至少为 k 的连续子数组的最大和。",lambda k,a:max(sum(b) for b in subarrays(a) if len(b)>=k),[(2,[1,-2,3,4]),(1,[-9]),(3,[-4,-2,-1]),(1,[0,0]),(8,list(range(-10,10)))],fmt="第一行 n k，第二行 n 个整数，1 ≤ k ≤ n ≤ 200000，|a_i| ≤ 10^9。")
seq("最长无重复数值区间", "滑动窗口", "求所有元素互不相同的连续子数组的最大长度。",lambda a:max(len(b) for b in subarrays(a) if len(b)==len(set(b))),cases=[[1,2,1,3,4],[9],[2,2,2],[-1,0,1],list(range(30))])
seq("乘积最大的连续区间", "动态规划", "求非空连续子数组的最大乘积。保证任意连续子数组乘积都在有符号 64 位整数范围内。",max_product,cases=[[2,3,-2,4],[-2],[-2,0,-1],[-2,-3,-4],[1]*100+[0,-1,-1]],bound="1 ≤ n ≤ 200000，-10 ≤ a_i ≤ 10")
seq("环形数组最大区间和", "动态规划", "数组首尾相接，选取非空连续一段，最多使用每个位置一次，求最大和。",lambda a:max(sum((a+a)[i:i+k]) for i in range(len(a)) for k in range(1,len(a)+1)),cases=[[5,-3,5],[-2],[-3,-2,-5],[0,0],list(range(-10,10))])
seq("接雨水总量", "前后缀最大值", "每根柱宽为 1，高度非负。求下雨后柱间能够容纳的总水量。",lambda a:sum(min(max(a[:i+1]),max(a[i:]))-x for i,x in enumerate(a)),cases=[[0,1,0,2,1,0,1,3,2,1,2,1],[0],[3,3,3],[5,0,0,5],list(range(100))],bound="1 ≤ n ≤ 200000，0 ≤ a_i ≤ 10^9")
seq("柱状图最大矩形", "单调栈", "柱宽均为 1，求完全位于柱状图内的轴对齐矩形的最大面积。",lambda a:max(min(b)*len(b) for b in subarrays(a)),cases=[[2,1,5,6,2,3],[0],[5,5,5],[5,4,3,2,1],list(range(30))],bound="1 ≤ n ≤ 200000，0 ≤ a_i ≤ 10^9")
seq("左侧最近更小值的位置", "单调栈", "对每个元素，输出其左侧距离最近且值严格更小的元素下标（从 1 开始），不存在输出 0。",lambda a:line(next((j+1 for j in range(i-1,-1,-1) if a[j]<x),0) for i,x in enumerate(a)),out="一行 n 个整数。")
seq("每日升温等待", "单调栈", "每个元素代表一天温度，输出每一天还需等待几天才遇到严格更高温度；以后没有则为 0。",lambda a:line(next((j-i for j in range(i+1,len(a)) if a[j]>x),0) for i,x in enumerate(a)),out="一行 n 个整数。")
seq("最大下标宽度", "单调结构", "求满足 i≤j 且 a_i≤a_j 的最大 j-i。",lambda a:max(j-i for i,x in enumerate(a) for j in range(i,len(a)) if x<=a[j]))
seq("连续数值集合长度", "哈希", "忽略重复值和原有次序，求集合中连续整数序列的最大长度，如 {1,2,3,8} 的答案为 3。",lambda a:max(sum(1 for _ in g) for k,g in itertools.groupby(enumerate(sorted(set(a))),lambda p:p[1]-p[0])) )


def coin_min(k,a):
    dp=[0]+[10**9]*k
    for t in range(1,k+1):
        dp[t]=min((dp[t-x]+1 for x in a if x<=t),default=10**9)
    return dp[k] if dp[k]<10**9 else -1


def coin_ways(k,a,ordered=False):
    dp=[1]+[0]*k
    if ordered:
        for t in range(1,k+1):
            dp[t]=sum(dp[t-x] for x in a if x<=t)%MOD
    else:
        for x in a:
            for t in range(x,k+1):
                dp[t]=(dp[t]+dp[t-x])%MOD
    return dp[k]


COINS=[(11,[1,2,5]),(0,[2]),(3,[2,4]),(8,[3,5]),(100,[1,3,7])]
CF="第一行 n k，第二行 n 个互不相同的正整数面值，1 ≤ n ≤ 100，0 ≤ k ≤ 10000，1 ≤ a_i ≤ 10000。"
params_array("最少硬币数", "完全背包", "每种硬币无限枚，求凑成恰好 k 的最少枚数，无解输出 -1。",coin_min,COINS,fmt=CF)
params_array("硬币组合数量", "完全背包", "每种硬币无限枚，求凑成 k 的不同组合数。顺序不同算同一种，空组合算一种，答案模 1000000007。",coin_ways,COINS,fmt=CF)
params_array("有序金额拆分", "动态规划", "面值可重复使用，求总和为 k 的有序序列数量。次序不同算不同，空序列算一种，答案模 1000000007。",lambda k,a:coin_ways(k,a,True),COINS,fmt=CF)
params_array("子集和可达性", "0/1 背包", "每个位置至多选一次，判断能否选出总和恰好为 k 的子集，可选空集。",lambda k,a:yn(any(sum(b)==k for r in range(len(a)+1) for b in itertools.combinations(a,r))),[(7,[2,3,4]),(0,[1]),(3,[2,4]),(4,[2,2]),(100,list(range(1,13)))],fmt="第一行 n k，第二行 n 个正整数，1 ≤ n ≤ 200，0 ≤ k ≤ 20000，a_i ≤ 20000。",out="输出 YES 或 NO。")
seq("等和划分", "0/1 背包", "判断能否将全部元素分成两个和相等的子集，每个元素必须属于其中一个。",lambda a:yn(sum(a)%2==0 and any(sum(b)*2==sum(a) for r in range(len(a)+1) for b in itertools.combinations(a,r))),cases=[[1,5,11,5],[1],[2,2],[1,2,4],list(range(1,13))],bound="1 ≤ n ≤ 200，1 ≤ a_i ≤ 100",out="输出 YES 或 NO。")
seq("石子重量最小差", "0/1 背包", "将所有石子分成两组，允许一组为空，求两组重量和之差的绝对值最小值。",lambda a:min(abs(sum(a)-2*sum(b)) for r in range(len(a)+1) for b in itertools.combinations(a,r)),cases=[[2,7,4,1,8,1],[9],[2,2],[1,2,4],list(range(1,13))],bound="1 ≤ n ≤ 200，1 ≤ a_i ≤ 100")
seq("最长非递增子序列", "动态规划", "选择若干下标递增的元素，要求值非递增，求最大可选数量。",lambda a:lis([-x for x in a],strict=False))
seq("递增子序列最大和", "动态规划", "选择非空且严格递增的子序列，求其元素和的最大值。",lambda a:increasing_sum(a),cases=[[1,101,2,3,100],[9],[-5,-2,-9],[2,2,2],list(range(100))],bound="1 ≤ n ≤ 3000，|a_i| ≤ 10^9")
seq("股票无限次交易", "贪心", "每天价格给定，可多次买卖但至多持有一股，同一天可卖出后买入。求最大利润，可不交易。",lambda a:sum(max(0,y-x) for x,y in zip(a,a[1:])),POS,bound="1 ≤ n ≤ 200000，1 ≤ a_i ≤ 10^9")
seq("环形房屋选择", "动态规划", "房屋形成环，第一个和最后一个也相邻。选择互不相邻的房屋，求收益总和最大值，可不选。n=1 时可选唯一房屋。",lambda a:max((sum(a[i] for i in range(len(a)) if mask>>i&1) for mask in range(1<<len(a)) if len(a)==1 or all(not(mask>>i&1 and mask>>((i+1)%len(a))&1) for i in range(len(a))))),cases=[[2,3,2],[7],[1,2,3,1],[0,0],[1,5,1,5,1,5]],bound="1 ≤ n ≤ 200000，0 ≤ a_i ≤ 10^9")


def lis(a,strict=True):
    tails=[]
    for x in a:
        i=(bisect.bisect_left if strict else bisect.bisect_right)(tails,x)
        if i==len(tails): tails.append(x)
        else: tails[i]=x
    return len(tails)


def increasing_sum(a):
    dp=[]
    for i,x in enumerate(a):
        dp.append(x+max([0]+[dp[j] for j in range(i) if a[j]<x]))
    return max(dp)


def grid_components(a):
    n,m=len(a),len(a[0]); seen=set(); sizes=[]
    for i in range(n):
        for j in range(m):
            if a[i][j]!=1 or (i,j) in seen: continue
            q=[(i,j)]; seen.add((i,j))
            for x,y in q:
                for X,Y in [(x-1,y),(x+1,y),(x,y-1),(x,y+1)]:
                    if 0<=X<n and 0<=Y<m and a[X][Y]==1 and (X,Y) not in seen:
                        seen.add((X,Y)); q.append((X,Y))
            sizes.append(len(q))
    return sizes


GRIDS=[[[1,1,0],[0,1,0],[1,0,1]],[[0]],[[1]],[[1,1,1,1]],[[int((i+j)%3==0) for j in range(20)] for i in range(20)]]
matrixq("岛屿数量", "网格搜索", "矩阵只含 0 和 1，1 为陆地。上下左右相邻的陆地属于一个岛，求岛屿数。",lambda a:len(grid_components(a)),GRIDS)
matrixq("最大岛屿面积", "网格搜索", "矩阵只含 0 和 1，求上下左右连通的最大陆地块的格子数，没有陆地输出 0。",lambda a:max(grid_components(a),default=0),GRIDS)
matrixq("陆地总周长", "网格", "矩阵只含 0 和 1，单位正方格中 1 为陆地。求所有陆地与水或地图外部接触的边总长，内湖边界也计算。",lambda a:sum(4-sum(0<=X<len(a) and 0<=Y<len(a[0]) and a[X][Y]==1 for X,Y in [(i-1,j),(i+1,j),(i,j-1),(i,j+1)]) for i,row in enumerate(a) for j,x in enumerate(row) if x==1),GRIDS)
matrixq("网格最小路径和", "动态规划", "从左上角走到右下角，每次只能向右或向下，求路径上数值和的最小值，包含起止格。",lambda a:grid_path(a))
matrixq("障碍网格路径计数", "动态规划", "矩阵只含 0 和 1，1 为障碍。左上角到右下角只能向右或向下，不能走障碍，求路径数模 1000000007。起终点有障碍时为 0。",lambda a:grid_path(a,True),GRIDS)


def grid_path(a,count=False):
    dp=[0 if count else 10**30]*len(a[0])
    for i,row in enumerate(a):
        for j,x in enumerate(row):
            if count:
                dp[j]=0 if x else 1 if i==j==0 else (dp[j]+(dp[j-1] if j else 0))%MOD
            else:
                dp[j]=x if i==j==0 else x+min(dp[j],dp[j-1] if j else 10**30)
    return dp[-1]


def intervalq(title,topic,statement,solve,cases=None,out="输出一行一个整数。"):
    cases=cases or [[(1,4),(2,5),(5,8)],[(0,1)],[(1,2),(2,3),(3,4)],[(0,8),(1,3),(2,4),(7,9)],[(i,i+3) for i in range(30)]]
    add(title,topic,statement,"第一行 n，随后 n 行各 l r，1 ≤ n ≤ 200000，0 ≤ l < r ≤ 10^9。",out,
        lambda s:solve(list(zip(ints(s)[1::2],ints(s)[2::2]))),[str(len(a))+"\n"+"\n".join(map(line,a)) for a in cases])


intervalq("最少会议室", "扫描线", "每个会议占用半开时间段 [l,r)，同一时刻每个会议室只能接待一个会议，求所需最少会议室数。",lambda a:max(sum(l<=t<r for l,r in a) for t in set(x for p in a for x in p)))
intervalq("区间刺点", "贪心", "每个区间为闭区间 [l,r]，选择尽可能少的实数点，使每个区间至少包含一个选点。",lambda a:stab(a))
intervalq("删除最少重叠区间", "贪心", "区间按半开 [l,r) 解释，删除最少数量使剩余区间两两无重叠。",lambda a:len(a)-schedule(a))
intervalq("区间公共交集长度", "区间", "求所有闭区间共同交集的长度。空交集和只有单点的交集长度均为 0。",lambda a:max(0,min(r for l,r in a)-max(l for l,r in a)))
intervalq("被包含区间数", "排序扫描", "统计被其他一个区间包含的区间数量。包含允许端点相等；完全相同但下标不同的区间互相包含。",lambda a:sum(any(j!=i and L<=l and r<=R for j,(L,R) in enumerate(a)) for i,(l,r) in enumerate(a)))


def stab(a):
    end=-1; count=0
    for l,r in sorted(a,key=lambda p:p[1]):
        if l>end: count+=1; end=r
    return count


def schedule(a):
    end=-1; count=0
    for l,r in sorted(a,key=lambda p:p[1]):
        if l>=end: count+=1; end=r
    return count


scalar("楼梯跳法", "动态规划", "一次爬 1 或 2 级，求到达 n 级的不同方式数模 1000000007，n=0 时有一种空走法。", "一行 n，0 ≤ n ≤ 1000000。",lambda n:stairs(n),[5,0,1,2,1000])
scalar("铺满二行棋盘", "动态规划", "用 2×1 多米诺骨牌铺满 2×n 棋盘，可旋转，不重叠、不留空。求方案数模 1000000007，n=0 时为 1。", "一行 n，0 ≤ n ≤ 1000000。",lambda n:stairs(n),[4,0,1,2,1000])
scalar("整数拆分最大乘积", "动态规划", "将 n 拆成至少两个正整数之和，求这些整数的最大乘积。", "一行 n，2 ≤ n ≤ 58。",lambda n:integer_break(n),[10,2,3,8,58])
scalar("卡特兰括号计数", "组合动态规划", "用 n 对圆括号能构成多少个合法括号串？任何前缀左括号不少于右括号，总数相同，答案模 1000000007。", "一行 n，0 ≤ n ≤ 1000。",lambda n:math.comb(2*n,n)//(n+1)%MOD,[3,0,1,5,1000])
scalar("没有相邻一的二进制串", "动态规划", "求长度为 n 且不含连续两个 1 的二进制串个数，允许前导零，答案模 1000000007，空串算一种。", "一行 n，0 ≤ n ≤ 1000000。",lambda n:stairs(n+1),[3,0,1,4,1000])


def stairs(n):
    a,b=1,1
    for _ in range(n): a,b=b,(a+b)%MOD
    return a


def integer_break(n):
    dp=[0]*(n+1)
    for t in range(2,n+1): dp[t]=max(j*max(t-j,dp[t-j]) for j in range(1,t))
    return dp[n]


def two_strings(title,topic,statement,solve,cases=None,bound=2000,out="输出一行一个整数。"):
    cases=cases or ["abcde ace","a a","abc def","aaaa aa","abacaba bacab"]
    add(title,topic,statement,f"两行非空小写字母串，各长 1..{bound}。",out,lambda s:solve(*s.split()),cases)


two_strings("最长公共连续片段", "动态规划", "求两个字符串最长公共子串的长度，子串必须连续。",lambda a,b:max((j-i for i in range(len(a)) for j in range(i+1,len(a)+1) if a[i:j] in b),default=0))
two_strings("删除字符使两串相同", "动态规划", "一次可从任意一串删除一个字符，求使两串相同的最少删除次数。",lambda a,b:len(a)+len(b)-2*lcs(a,b))
two_strings("子序列匹配方案数", "动态规划", "从第一串选择若干下标递增的字符组成第二串，有多少种下标选择？答案模 1000000007。",lambda a,b:distinct_subsequence(a,b))
two_strings("交错合并的最短长度", "动态规划", "求一个同时包含两串为子序列的最短字符串的长度。",lambda a,b:len(a)+len(b)-lcs(a,b))
stringq("括号最少补全", "贪心", "在任意位置添加圆括号，使原串成为合法括号串，求最少添加字符数。",lambda s:paren_insert(s),["())","(",")","()()","))(("],fmt="一行仅含 ( 和 ) 的非空串，长度 ≤ 200000。")
stringq("删除相邻重复字符", "栈", "不断删除相邻且相同的两个字符，直到不能删除。输出最终字符串，若为空输出 EMPTY。",lambda s:cancel_pairs(s),["abbaca","a","aa","abccba","azxxzy"],fmt="一行非空小写串，长度 ≤ 200000。")
stringq("解码数字消息", "动态规划", "1..26 对应 A..Z，求数字串可解码为多少个字母序列，单独的 0 和带前导零的两位数不能解码。答案模 1000000007。",lambda s:decode(s),["226","0","10","100","11111111111111111111"],fmt="一行非空数字串，长度 ≤ 200000。")
stringq("最长交替二进制片段", "扫描", "求相邻字符都不相同的最长连续片段长度。",lambda s:max(j-i for i in range(len(s)) for j in range(i+1,len(s)+1) if all(s[k]!=s[k+1] for k in range(i,j-1))),["0010100","0","1","11111","01010101"],fmt="一行非空二进制串，长度 ≤ 200000。")
seq("最优合并代价", "堆与贪心", "每次取两个非负数合并为它们的和，本次代价等于该和。将全部数合为一个数，求最小总代价。只有一个数时代价为 0。",lambda a:merge_cost(a),POS,bound="1 ≤ n ≤ 200000，1 ≤ a_i ≤ 10^9")
seq("最长山峰子序列", "动态规划", "选择先严格上升再严格下降的子序列，上升和下降两段都至少有一条变化边。求最大长度，不存在输出 0。",lambda a:mountain(a),cases=[[1,3,5,4,2],[1],[1,2,3],[3,2,1],[1,4,2,5,3,2,1]],bound="1 ≤ n ≤ 3000，|a_i| ≤ 10^9")


def lcs(a,b):
    dp=[0]*(len(b)+1)
    for x in a:
        old=dp[:]
        for j,y in enumerate(b): dp[j+1]=old[j]+1 if x==y else max(old[j+1],dp[j])
    return dp[-1]


def distinct_subsequence(a,b):
    dp=[1]+[0]*len(b)
    for x in a:
        for j in range(len(b)-1,-1,-1):
            if x==b[j]: dp[j+1]=(dp[j+1]+dp[j])%MOD
    return dp[-1]


def paren_insert(s):
    balance=missing=0
    for c in s:
        balance+=1 if c=='(' else -1
        if balance<0: missing+=1; balance=0
    return missing+balance


def cancel_pairs(s):
    stack=[]
    for c in s:
        if stack and stack[-1]==c: stack.pop()
        else: stack.append(c)
    return ''.join(stack) or 'EMPTY'


def decode(s):
    a,b=1,int(s[0]!='0')
    for i in range(1,len(s)): a,b=b,((b if s[i]!='0' else 0)+(a if 10<=int(s[i-1:i+1])<=26 else 0))%MOD
    return b


def merge_cost(a):
    h=a[:]; heapq.heapify(h); total=0
    while len(h)>1:
        v=heapq.heappop(h)+heapq.heappop(h); total+=v; heapq.heappush(h,v)
    return total


def mountain(a):
    left=[1]*len(a); right=[1]*len(a)
    for i in range(len(a)):
        left[i]=1+max((left[j] for j in range(i) if a[j]<a[i]),default=0)
    for i in range(len(a)-1,-1,-1):
        right[i]=1+max((right[j] for j in range(i+1,len(a)) if a[j]<a[i]),default=0)
    return max((left[i]+right[i]-1 for i in range(len(a)) if left[i]>1 and right[i]>1),default=0)


LEVEL = "HARD"
GRAPH_CASES=[(5,[(1,2,2),(1,3,4),(2,3,1),(3,4,3),(4,5,2),(2,5,10)]),
             (2,[]),(2,[(1,2,7)]),(4,[(1,2,1),(2,3,1),(3,1,1)]),
             (12,[(i,i+1,i%5+1) for i in range(1,12)]+[(1,6,2),(3,9,4),(5,12,3)]),
             (8,[(i,i+1,1) for i in range(1,8)]),
             (4,[(1,2,1),(1,3,1),(2,4,1),(3,4,1)])]


def graphq(title,topic,statement,solve,directed=True,weighted=True,cases=None,out="输出一行一个整数。"):
    cases=cases or GRAPH_CASES
    add(title,topic,statement,
        f"第一行 n m，随后 m 行各 {'u v w' if weighted else 'u v'}，表示{'有向' if directed else '无向'}边。2 ≤ n ≤ 200，0 ≤ m ≤ 2000，顶点编号 1..n，无自环、无重复边。"+("1 ≤ w ≤ 1000000。" if weighted else "每条边长度为 1。"),out,
        lambda s:(lambda rows:solve(int(rows[0][0]),[tuple(map(int,r)) if weighted else (*map(int,r),1) for r in rows[1:]]))([r.split() for r in s.splitlines()]),
        [f"{n} {len(edges)}\n"+"\n".join(line(e if weighted else e[:2]) for e in edges) for n,edges in cases])


INF=10**30


def floyd(n,edges,directed=True):
    d=[[INF]*n for _ in range(n)]
    for i in range(n): d[i][i]=0
    for u,v,w in edges:
        d[u-1][v-1]=min(d[u-1][v-1],w)
        if not directed: d[v-1][u-1]=min(d[v-1][u-1],w)
    for k in range(n):
        for i in range(n):
            for j in range(n): d[i][j]=min(d[i][j],d[i][k]+d[k][j])
    return d


def scc(n,edges):
    d=floyd(n,edges); groups=[]; seen=set()
    for i in range(n):
        if i in seen: continue
        group={j for j in range(n) if d[i][j]<INF and d[j][i]<INF}
        seen|=group; groups.append(group)
    return groups


def components(n,edges,removed_vertex=-1):
    adj=[[] for _ in range(n)]
    for u,v,w in edges:
        if removed_vertex in (u-1,v-1): continue
        adj[u-1].append(v-1); adj[v-1].append(u-1)
    seen={removed_vertex}; groups=[]
    for i in range(n):
        if i in seen: continue
        q=[i]; seen.add(i)
        for u in q:
            for v in adj[u]:
                if v not in seen: seen.add(v); q.append(v)
        groups.append(q)
    return groups


def bipartite(n,edges):
    adj=[[] for _ in range(n)]; color={}
    for u,v,w in edges: adj[u-1].append(v-1); adj[v-1].append(u-1)
    for i in range(n):
        if i in color: continue
        q=[i]; color[i]=0
        for u in q:
            for v in adj[u]:
                if v not in color: color[v]=1-color[u]; q.append(v)
                elif color[v]==color[u]: return False
    return True


def shortest_count(n,edges):
    adj=[[] for _ in range(n)]
    for u,v,w in edges: adj[u-1].append((v-1,w))
    d=[INF]*n; ways=[0]*n; d[0]=0; ways[0]=1; h=[(0,0)]
    while h:
        distance,u=heapq.heappop(h)
        if distance!=d[u]: continue
        for v,w in adj[u]:
            if distance+w<d[v]:
                d[v]=distance+w; ways[v]=ways[u]; heapq.heappush(h,(d[v],v))
            elif distance+w==d[v]: ways[v]=(ways[v]+ways[u])%MOD
    return ways[-1]


def maxflow(n,edges):
    capacity=[[0]*n for _ in range(n)]
    for u,v,w in edges: capacity[u-1][v-1]+=w
    total=0
    while True:
        parent=[-1]*n; parent[0]=0; q=[0]
        for u in q:
            for v in range(n):
                if parent[v]<0 and capacity[u][v]>0: parent[v]=u; q.append(v)
        if parent[-1]<0: return total
        f=INF; v=n-1
        while v: u=parent[v]; f=min(f,capacity[u][v]); v=u
        v=n-1
        while v: u=parent[v]; capacity[u][v]-=f; capacity[v][u]+=f; v=u
        total+=f


def dag_dp(n,edges,count=False):
    adj=[[] for _ in range(n)]; indeg=[0]*n
    for u,v,w in edges: adj[u-1].append((v-1,w)); indeg[v-1]+=1
    q=[i for i in range(n) if indeg[i]==0]; dp=[0 if count else -INF]*n; dp[0]=1 if count else 0
    for u in q:
        for v,w in adj[u]:
            if count: dp[v]=(dp[v]+dp[u])%MOD
            elif dp[u]>-INF: dp[v]=max(dp[v],dp[u]+w)
            indeg[v]-=1
            if indeg[v]==0: q.append(v)
    return dp[-1] if count or dp[-1]>-INF else -1


graphq("最短路方案计数", "Dijkstra 与计数", "求从 1 到 n 的最短路径条数，边权为正。不同顶点序列视为不同路径，答案模 1000000007，不可达为 0。",shortest_count)
graphq("强连通分量个数", "强连通分量", "有向图中互相可达的顶点构成强连通分量，单个孤立点也算一个，求分量总数。",lambda n,e:len(scc(n,e)),weighted=False)
graphq("最少起始传播点", "缩点", "可选若干起始顶点，消息沿有向边传播。求使所有顶点最终收到消息的最少起始顶点数。",lambda n,e:sum(not any(v-1 in g and u-1 not in g for u,v,w in e) for g in scc(n,e)),weighted=False)
graphq("所有可达有序点对", "传递闭包", "统计 u≠v 且 u 能沿有向边到达 v 的有序点对 (u,v) 数量。",lambda n,e:sum(i!=j and d<INF for i,row in enumerate(floyd(n,e)) for j,d in enumerate(row)),weighted=False)
graphq("桥的数量", "桥与连通性", "删除一条边后，无向图的连通分量数若增加，该边称桥。求桥的数量。",lambda n,e:sum(len(components(n,e[:i]+e[i+1:]))>len(components(n,e)) for i in range(len(e))),directed=False,weighted=False)
graphq("割点数量", "割点", "删除某个顶点及其关联边后，连通分量数量增加则该顶点是割点。求割点数。",lambda n,e:sum(len(components(n,e,i))>len(components(n,e)) for i in range(n)),directed=False,weighted=False)
graphq("二分图判定", "图染色", "判断无向图顶点能否染成两色，使每条边两端颜色不同，可不连通。",lambda n,e:yn(bipartite(n,e)),directed=False,weighted=False,out="输出 YES 或 NO。")
graphq("通信中心最小半径", "全源最短路", "选一个顶点，使其到所有顶点的最短距离最大值尽可能小。输出这个最小值；图不连通输出 -1。",lambda n,e:(lambda r:-1 if r>=INF else r)(min(map(max,floyd(n,e,False)))),directed=False)
graphq("有向网络最大流", "最大流", "边权表示容量，源点 1、汇点 n。流量满足容量限制和中间顶点流量守恒，求最大流量。",maxflow)
graphq("最小断路容量", "最小割", "每条有向边的边权是删除代价。删除若干边使 1 无法到达 n，求最小总代价，已经不通时为 0。",maxflow)
graphq("互不共边的路线", "单位容量网络流", "求 1 到 n 最多有多少条两两不共用有向边的路径，路径可共享顶点。",maxflow,weighted=False)
DAGS=[(n,[(u,v,w) for u,v,w in e if u<v]) for n,e in GRAPH_CASES]
graphq("有向无环图路径数", "拓扑动态规划", "保证图为有向无环图。求从 1 到 n 的不同路径条数模 1000000007，不可达为 0。",lambda n,e:dag_dp(n,e,True),weighted=False,cases=DAGS)
graphq("有向无环图最长路", "拓扑动态规划", "保证图为有向无环图，求从 1 到 n 的最长路径边权和，不可达输出 -1。",dag_dp,cases=DAGS)
graphq("最小瓶颈路径", "Floyd 变式", "路径代价为路径上最大边权。求 1 到 n 的最小路径代价，无路径输出 -1。",lambda n,e:bottleneck(n,e))
graphq("恰好 k 条边的最短路", "分层动态规划", "令 k=n。求从 1 到 n 恰好经过 k 条边的最小权值和，可重复经过顶点和边，无解输出 -1。",lambda n,e:exact_edges(n,e),cases=GRAPH_CASES+[(3,[(1,2,1),(2,1,1),(1,3,2)])])


def bottleneck(n,e):
    d=[[INF]*n for _ in range(n)]
    for i in range(n): d[i][i]=0
    for u,v,w in e: d[u-1][v-1]=w
    for k in range(n):
        for i in range(n):
            for j in range(n): d[i][j]=min(d[i][j],max(d[i][k],d[k][j]))
    return d[0][-1] if d[0][-1]<INF else -1


def exact_edges(n,e):
    dp=[INF]*n; dp[0]=0
    for _ in range(n):
        nxt=[INF]*n
        for u,v,w in e: nxt[v-1]=min(nxt[v-1],dp[u-1]+w)
        dp=nxt
    return dp[-1] if dp[-1]<INF else -1


TREES=[(5,[(1,2),(1,3),(3,4),(3,5)]),(1,[]),(2,[(1,2)]),(7,[(1,i) for i in range(2,8)]),(100,[(i,i+1) for i in range(1,100)])]


def treeq(title,topic,statement,solve,out="输出一行一个整数。"):
    add(title,topic,statement,"第一行 n，随后 n-1 行各 u v 表示无向边，保证为一棵树，1 ≤ n ≤ 200000，顶点 1..n，所有边长 1。",out,
        lambda s:(lambda a:solve(a[0],list(zip(a[1::2],a[2::2]))))(ints(s)),[str(n)+"\n"+"\n".join(map(line,e)) for n,e in TREES])


def tree_info(n,e):
    adj=[[] for _ in range(n)]
    for u,v in e: adj[u-1].append(v-1); adj[v-1].append(u-1)
    parent=[-1]*n; order=[0]; parent[0]=0; depth=[0]*n; size=[1]*n
    for u in order:
        for v in adj[u]:
            if v!=parent[u]: parent[v]=u; depth[v]=depth[u]+1; order.append(v)
    for u in order[:0:-1]: size[parent[u]]+=size[u]
    return adj,parent,order,depth,size


def tree_distances(n,e):
    adj,p,order,depth,size=tree_info(n,e); dist=[0]*n; dist[0]=sum(depth)
    for u in order[1:]: dist[u]=dist[p[u]]+n-2*size[u]
    return dist


def independent_tree(n,e):
    adj,p,order,depth,size=tree_info(n,e); yes=[1]*n; no=[0]*n
    for u in order[:0:-1]:
        yes[p[u]]+=no[u]; no[p[u]]+=max(yes[u],no[u])
    return max(yes[0],no[0])


def tree_matching(n,e):
    adj,p,order,depth,size=tree_info(n,e); used=set(); result=0
    for u in order[:0:-1]:
        if u not in used and p[u] not in used: used|={u,p[u]}; result+=1
    return result


def centroid(n,e):
    adj,p,order,d,size=tree_info(n,e)
    return min(range(n),key=lambda u:(max([n-size[u]]+[size[v] for v in adj[u] if p[v]==u]),u))+1


def independent_count(n,e):
    adj,p,order,d,size=tree_info(n,e); yes=[1]*n; no=[1]*n
    for u in order[:0:-1]: yes[p[u]]=yes[p[u]]*no[u]%MOD; no[p[u]]=no[p[u]]*(yes[u]+no[u])%MOD
    return (yes[0]+no[0])%MOD


treeq("树上各点距离和", "换根动态规划", "对每个顶点，求它到所有顶点的距离之和，按顶点编号输出。",lambda n,e:line(tree_distances(n,e)),out="一行 n 个整数。")
treeq("树的重心", "树形动态规划", "删除一个顶点后，最大连通块的顶点数量尽可能小。输出满足条件的最小顶点编号。",centroid)
treeq("树上最大独立集", "树形动态规划", "选尽可能多的顶点，要求任意两个被选顶点之间没有边相连，求最大数量。",independent_tree)
treeq("树上最小顶点覆盖", "树形动态规划", "选尽可能少的顶点，使每条边至少有一个端点被选，求最少数量。",lambda n,e:n-independent_tree(n,e))
treeq("树上最大匹配", "树形贪心", "选择尽可能多的边，要求任意两条被选边不共享端点，求最多可选边数。",tree_matching)
treeq("树上独立集计数", "树形动态规划", "求互不相邻顶点子集的数量，空集算一种，答案模 1000000007。",independent_count)
treeq("所有无序点对距离和", "边贡献", "对所有 u<v，求 u 与 v 的树上距离之和。",lambda n,e:sum(tree_distances(n,e))//2)
treeq("树上最优集会点", "换根动态规划", "每个顶点住一人，选择一个顶点开会，求所有人到该点距离总和的最小值。",lambda n,e:min(tree_distances(n,e)))
treeq("偶数距离点对", "二分染色", "统计 u<v 且树上距离为偶数的点对数量。",lambda n,e:(lambda c:sum(v*(v-1)//2 for v in c.values()))(collections.Counter(x%2 for x in tree_info(n,e)[3])))
treeq("根到叶路径深度总和", "树遍历", "以 1 为根，没有孩子的顶点为叶子。求所有叶子的深度之和，根的深度为 0。",lambda n,e:(lambda t:sum(t[3][u] for u in range(n) if all(v==t[1][u] for v in t[0][u])))(tree_info(n,e)))


def query_cases(mode):
    rng=random.Random(20260908)
    cases=[]
    for n in (5,1,2,9,300):
        a=[rng.randint(-100,100) for _ in range(n)]; commands=[]
        for i in range(30):
            l=rng.randint(1,n); r=rng.randint(l,n)
            if mode=="point": commands.append(f"1 {l} {rng.randint(-100,100)}" if i%3==0 else f"2 {l} {r}")
            elif mode=="range": commands.append(f"1 {l} {r} {rng.randint(-100,100)}" if i%3==0 else f"2 {l} {r}")
            elif mode=="kth": commands.append(f"{l} {r} {rng.randint(1,r-l+1)}")
            else: commands.append(f"{l} {r}")
        cases.append(f"{n} {len(commands)}\n{line(a)}\n"+"\n".join(commands))
    return cases


def query_solve(s,mode):
    rows=s.splitlines(); a=ints(rows[1]); answers=[]
    for row in rows[2:]:
        v=ints(row)
        if mode=="point":
            if v[0]==1: a[v[1]-1]+=v[2]
            else: answers.append(sum(a[v[1]-1:v[2]]))
        elif mode=="range":
            if v[0]==1:
                for i in range(v[1]-1,v[2]): a[i]+=v[3]
            else: answers.append(sum(a[v[1]-1:v[2]]))
        elif mode=="kth": answers.append(sorted(a[v[0]-1:v[1]])[v[2]-1])
        else: answers.append(len(set(a[v[0]-1:v[1]])))
    return "\n".join(map(str,answers))


QFMT="第一行 n q，第二行 n 个整数。随后 q 行操作。1 ≤ n,q ≤ 200000，初值和修改量绝对值 ≤ 10^6，下标从 1 开始，1 ≤ l ≤ r ≤ n，保证至少一次查询。"
add("单点累加与区间求和","树状数组","维护数组，操作 1 i x 表示 a_i 加 x；操作 2 l r 查询闭区间和。",QFMT,"每次查询输出一行整数。",lambda s:query_solve(s,"point"),query_cases("point"))
add("区间累加与区间求和","懒标记线段树","维护数组，操作 1 l r x 表示区间 [l,r] 所有元素加 x；操作 2 l r 查询区间和。",QFMT,"每次查询输出一行整数。",lambda s:query_solve(s,"range"),query_cases("range"))
add("静态区间第 k 小","可持久化线段树","每次查询 l r k，输出 a_l..a_r 排序后的第 k 小值，重复值占多个位置，数组不修改。",QFMT+"本题无修改，每行查询 l r k，1 ≤ k ≤ r-l+1。","每次查询输出一行整数。",lambda s:query_solve(s,"kth"),query_cases("kth"))
add("区间不同数值查询","离线查询","每次查询 l r，输出 a_l..a_r 中不同值数量，数组不修改。",QFMT+"本题无修改，每行查询 l r。","每次查询输出一行整数。",lambda s:query_solve(s,"distinct"),query_cases("distinct"))


def lca_cases():
    cases=[]
    for n,e in TREES:
        queries=[(1,n),(n,n),(max(1,n//2),n),(1,1),(max(1,n-1),n)]
        cases.append(f"{n} {len(queries)}\n"+"\n".join(line(p) for p in e+queries))
    return cases


def lca_solve(s):
    z=ints(s); n,q=z[:2]; pairs=list(zip(z[2::2],z[3::2])); e=pairs[:n-1]
    adj,p,order,d,size=tree_info(n,e); answers=[]
    for u,v in pairs[n-1:]:
        u-=1; v-=1
        while u!=v:
            if d[u]>=d[v]: u=p[u]
            else: v=p[v]
        answers.append(u+1)
    return "\n".join(map(str,answers))


add("最近公共祖先查询","倍增","以 1 为根，回答多组顶点 u,v 的最近公共祖先，顶点可以是自己的祖先。","第一行 n q；随后 n-1 行无向边；最后 q 行各 u v。保证为树，1 ≤ n,q ≤ 200000，顶点编号 1..n。","每个查询输出一行祖先编号。",lca_solve,lca_cases())


def squareq(title,topic,statement,solve,cases,bound=18):
    add(title,topic,statement,f"第一行 n，随后 n 行各 n 个整数，1 ≤ n ≤ {bound}，0 ≤ a_ij ≤ 1000000。","输出一行一个整数。",lambda s:(lambda z:solve([z[1+i*z[0]:1+(i+1)*z[0]] for i in range(z[0])]))(ints(s)),[str(len(a))+"\n"+"\n".join(map(line,a)) for a in cases])


SQUARES=[[[0,3,8],[4,0,2],[5,1,0]],[[0]],[[0,7],[3,0]],[[0 if i==j else 1 for j in range(4)] for i in range(4)],[[0 if i==j else (i*7+j*3)%19+1 for j in range(10)] for i in range(10)]]


def tsp(a):
    n=len(a)
    @functools.lru_cache(None)
    def f(mask,u):
        if mask==(1<<n)-1:return a[u][0]
        return min(a[u][v]+f(mask|1<<v,v) for v in range(n) if not mask>>v&1)
    return 0 if n==1 else f(1,0)


def assignment(a,count=False):
    n=len(a); dp=[0 if count else INF]*(1<<n); dp[0]=1 if count else 0
    for mask in range((1<<n)-1):
        i=mask.bit_count()
        for j in range(n):
            if mask>>j&1: continue
            nxt=mask|1<<j
            if count:
                if a[i][j]: dp[nxt]=(dp[nxt]+dp[mask])%MOD
            else: dp[nxt]=min(dp[nxt],dp[mask]+a[i][j])
    return dp[-1]


squareq("旅行商最短巡回","状态压缩动态规划","a_ij 表示城市 i 到 j 的费用，可不对称，主对角线为 0。从城市 1 出发，恰好访问每个其他城市一次并回到 1，求最小费用。n=1 时为 0。",tsp,SQUARES)
squareq("任务指派最小费用","状态压缩动态规划","n 个工人和 n 个任务，a_ij 为工人 i 完成任务 j 的费用。每人恰好一个任务，每任务恰好一个工人，求最小总费用。",assignment,[[[9,2,7],[6,4,3],[5,8,1]],[[5]],[[0,1],[1,0]],[[1]*4 for _ in range(4)],[[((i*7+j*3)%19)+1 for j in range(10)] for i in range(10)]])
squareq("二分图完美匹配计数","状态压缩动态规划","矩阵仅含 0/1，a_ij=1 表示左部 i 可与右部 j 匹配。求使每个点恰好匹配一次的方案数模 1000000007。",lambda a:assignment(a,True),[[[1,1,0],[0,1,1],[1,0,1]],[[0]],[[1]],[[1]*4 for _ in range(4)],[[int((i+j)%3!=0) for j in range(10)] for i in range(10)]])
stringq("回文分割最少切割","区间动态规划","将字符串切成若干非空回文子串，求最少切割次数。整串是回文时为 0。",lambda s:pal_cuts(s),["aab","a","abccba","abcdef","abacdcaba"],fmt="一行非空小写串，长度 ≤ 2000。")
seq("矩阵链乘法最小代价","区间动态规划","给定 n 个正整数 d_1..d_n，表示 n-1 个矩阵，第 i 个矩阵大小为 d_i×d_(i+1)。两个矩阵 p×q 和 q×r 相乘代价为 pqr。仅调整括号，求最小总代价；n=1 时没有矩阵，答案 0。",lambda a:matrix_chain(a),cases=[[10,30,5,60],[4],[2,3],[5,5,5,5],[i%7+1 for i in range(30)]],bound="1 ≤ n ≤ 200，1 ≤ d_i ≤ 1000")
seq("戳气球最大收益","区间动态规划","每次删除一个元素，获得该元素与当前左右最近未删除元素的乘积。两端外侧固定视为 1。必须全部删除，求最大总收益。",lambda a:balloons(a),cases=[[3,1,5,8],[0],[5],[1,1,1],[i%5 for i in range(20)]],bound="1 ≤ n ≤ 200，0 ≤ a_i ≤ 100")
seq("交易冷冻期","状态机动态规划","每天价格给定，可多次交易且最多持有一股。卖出后第二天不能买入。求最大利润，可不交易。",lambda a:cooldown(a),POS,bound="1 ≤ n ≤ 200000，1 ≤ a_i ≤ 10^9")
seq("至多两次股票交易","状态机动态规划","最多完成两次先买后卖的交易，任意时刻最多持有一股，同一天可先卖后买，求最大利润，可不交易。",lambda a:stock_two(a),POS,bound="1 ≤ n ≤ 200000，1 ≤ a_i ≤ 10^9")
scalar("不含数字四的整数计数","数位动态规划","统计 0 到 n 中十进制表示不含数字 4 的整数个数，0 也计入。","一行 n，0 ≤ n ≤ 10^18。",lambda n:digit_no_four(n),[50,0,4,444,10**18])
add("通配符全串匹配","动态规划","第一行为文本，第二行为模式。模式中的 ? 匹配任意一个字符，* 匹配任意长度的字符串（可空）。判断模式是否匹配整个文本。","两行非空字符串，长度各不超过 2000。文本仅小写字母，模式仅小写字母、?、*。","匹配输出 YES，否则 NO。",lambda s:yn(wildcard(*s.split())),["abc a*c","a ?","abc a*d","abc ***","ab a?b"])


def pal_cuts(s):
    dp=[-1]+[len(s)]*len(s)
    for j in range(1,len(s)+1): dp[j]=min(dp[i]+1 for i in range(j) if s[i:j]==s[i:j][::-1])
    return dp[-1]


def matrix_chain(a):
    @functools.lru_cache(None)
    def f(l,r):
        return 0 if r-l<2 else min(f(l,k)+f(k,r)+a[l]*a[k]*a[r] for k in range(l+1,r))
    return f(0,len(a)-1)


def balloons(a):
    a=[1]+a+[1]
    @functools.lru_cache(None)
    def f(l,r):
        return max((f(l,k)+f(k,r)+a[l]*a[k]*a[r] for k in range(l+1,r)),default=0)
    return f(0,len(a)-1)


def cooldown(a):
    hold=-INF; sold=-INF; rest=0
    for x in a: hold,sold,rest=max(hold,rest-x),hold+x,max(rest,sold)
    return max(rest,sold)


def stock_two(a):
    buy1=buy2=-INF; sell1=sell2=0
    for x in a: buy1=max(buy1,-x); sell1=max(sell1,buy1+x); buy2=max(buy2,sell1-x); sell2=max(sell2,buy2+x)
    return sell2


def digit_no_four(n):
    digits=str(n)
    @functools.lru_cache(None)
    def f(i,tight):
        if i==len(digits): return 1
        top=int(digits[i]) if tight else 9
        return sum(f(i+1,tight and x==top) for x in range(top+1) if x!=4)
    return f(0,True)


def wildcard(a,b):
    dp=[True]+[False]*len(a)
    for c in b:
        old=dp[:]; dp[0]=old[0] and c=='*'
        for j,x in enumerate(a): dp[j+1]=(old[j+1] or dp[j]) if c=='*' else old[j] and c in ('?',x)
    return dp[-1]


stringq("不同子串数量","后缀结构","求不同的非空连续子串数量，相同内容只计算一次。",lambda s:len({s[i:j] for i in range(len(s)) for j in range(i+1,len(s)+1)}),["ababa","a","aaaaa","abcdef","mississippi"],fmt="一行非空小写串，长度 ≤ 200000。")
stringq("回文子串总数","Manacher","统计所有回文连续子串，起止下标不同就算不同，即使内容相同。",lambda s:sum(s[i:j]==s[i:j][::-1] for i in range(len(s)) for j in range(i+1,len(s)+1)),["ababa","a","aaaaa","abcdef","mississippi"],fmt="一行非空小写串，长度 ≤ 200000。")
stringq("字符串最短循环节","KMP","求最短非空字符串 t 的长度，使原串可以由若干个完整的 t 拼接而成。",lambda s:next(k for k in range(1,len(s)+1) if len(s)%k==0 and s==s[:k]*(len(s)//k)),["abcabc","a","aaaaa","abcdef","abababa"],fmt="一行非空小写串，长度 ≤ 200000。")
stringq("前缀函数数组","KMP","对每个长度 i 的前缀，求它的最长相等真前缀和后缀的长度。真前缀不能是整个前缀本身，无解为 0。",lambda s:line(prefix_function(s)),["ababcabab","a","aaaaa","abcdef","abacaba"],fmt="一行非空小写串，长度 ≤ 200000。",out="输出一行 n 个整数，对应 i=1..n。")
stringq("后缀与原串的公共前缀","Z 算法","对每个位置 i，求从 i 开始的后缀与整个字符串的最长公共前缀长度。位置从 1 开始，第一个输出为 n。",lambda s:line(next((j for j in range(len(s)-i) if s[j]!=s[i+j]),len(s)-i) for i in range(len(s))),["ababcabab","a","aaaaa","abcdef","abacaba"],fmt="一行非空小写串，长度 ≤ 200000。",out="输出一行 n 个整数。")


def prefix_function(s):
    pi=[0]*len(s)
    for i in range(1,len(s)):
        j=pi[i-1]
        while j and s[i]!=s[j]: j=pi[j-1]
        if s[i]==s[j]: j+=1
        pi[i]=j
    return pi


def totients(n):
    phi=list(range(n+1))
    for p in range(2,n+1):
        if phi[p]!=p: continue
        for j in range(p,n+1,p): phi[j]-=phi[j]//p
    return phi


scalar("欧拉函数前缀和","线性筛","φ(i) 表示 1..i 中与 i 互质的整数个数，规定 φ(1)=1。求 φ(1)+…+φ(n)。","一行 n，1 ≤ n ≤ 1000000。",lambda n:sum(totients(n)),[10,1,2,100,1000000])
scalar("两条同余方程","扩展欧几里得","求最小非负整数 x，使 x mod m=a 且 x mod n=b；无解输出 -1。模数不保证互质。","一行 a m b n，1 ≤ m,n ≤ 10^9，0 ≤ a < m，0 ≤ b < n。",lambda a,m,b,n:crt(a,m,b,n),["2 3 3 5","0 1 0 1","1 2 0 2","2 4 6 8","123 1000000000 456 999999999"])
scalar("线性同余最小解","扩展欧几里得","求最小非负整数 x，使 ax 与 b 模 m 同余，无解输出 -1。","一行 a b m，0 ≤ a,b ≤ 10^12，1 ≤ m ≤ 10^12。",lambda a,b,m:linear_congruence(a,b,m),["6 4 10","0 0 7","0 1 7","3 2 1","1000000000000 1 999999999999"])
scalar("大组合数取模","阶乘与逆元","计算 C(n,k) mod 1000000007。","一行 n k，0 ≤ k ≤ n ≤ 1000000。",lambda n,k:math.comb(n,k)%MOD,["10 3","0 0","1000000 2","1000 500","20 20"])
scalar("互质无序整数对","欧拉筛","统计 1 ≤ a < b ≤ n 且 gcd(a,b)=1 的整数对数量。","一行 n，1 ≤ n ≤ 1000000。",lambda n:sum(totients(n)[2:]),[10,1,2,100,1000000])


def crt(a,m,b,n):
    g=math.gcd(m,n)
    if (b-a)%g: return -1
    k=((b-a)//g*pow(m//g,-1,n//g))%(n//g) if n//g>1 else 0
    return (a+m*k)%math.lcm(m,n)


def linear_congruence(a,b,m):
    g=math.gcd(a,m)
    if b%g: return -1
    return b//g*pow(a//g,-1,m//g)%(m//g) if m//g>1 else 0


def generate():
    counts=collections.Counter(q['difficulty'] for q in QUESTIONS)
    assert counts==dict.fromkeys(['EASY','BASIC','INTERMEDIATE','HARD'],50),counts
    assert len({q['title'] for q in QUESTIONS})==200
    for q in QUESTIONS:
        solve=q.pop('_solve'); cases=q.pop('_cases'); tests=[]
        for i,data in enumerate(cases):
            try: answer=str(solve(data)).strip()
            except Exception as error: raise RuntimeError(q['title']) from error
            assert answer,q['title']
            tests.append(dict(input=data.strip()+'\n',expectedOutput=answer+'\n',sample=i==0))
        assert len({t['expectedOutput'] for t in tests})>1, f"{q['title']} 需要不同结果的测试点"
        q['testcases']=tests
    path=ROOT/'app/api/src/main/resources/practice/extended-catalog.json'
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(QUESTIONS,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'已生成 {len(QUESTIONS)} 道题，{sum(len(q["testcases"]) for q in QUESTIONS)} 个测试点：{dict(counts)}')


if __name__=='__main__':
    generate()
