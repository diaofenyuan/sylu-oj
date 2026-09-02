// 代码模板管理
export function useCodeTemplates() {
  // 基础模板
  const basicTemplates = {
    C: {
      name: 'C标准输入输出',
      code: `#include <stdio.h>

int main() {
    int a, b;
    scanf("%d %d", &a, &b);
    printf("%d\\n", a + b);
    return 0;
}`
    },
    CPP: {
      name: 'C++标准输入输出',
      code: `#include <iostream>
using namespace std;

int main() {
    int a, b;
    cin >> a >> b;
    cout << a + b << endl;
    return 0;
}`
    },
    PYTHON: {
      name: 'Python标准输入输出',
      code: `import sys

# 读取输入
data = sys.stdin.read().split()
a, b = int(data[0]), int(data[1])

# 输出结果
print(a + b)`
    },
    JAVA: {
      name: 'Java标准输入输出',
      code: `import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        int a = in.nextInt();
        int b = in.nextInt();
        System.out.println(a + b);
        in.close();
    }
}`
    }
  }

  // 高级代码片段库
  const snippets = {
    CPP: [
      {
        name: '快速输入输出',
        desc: '关闭同步加速IO',
        code: `ios::sync_with_stdio(false);
cin.tie(nullptr);`
      },
      {
        name: '向量/数组',
        desc: '常用的vector操作',
        code: `vector<int> arr(n);
for (int i = 0; i < n; i++) {
    cin >> arr[i];
}`
      },
      {
        name: '快速排序',
        desc: '使用STL sort',
        code: `sort(arr.begin(), arr.end());  // 升序
sort(arr.begin(), arr.end(), greater<int>());  // 降序`
      },
      {
        name: '二分查找',
        desc: 'STL二分查找',
        code: `// 查找第一个>=target的位置
auto it = lower_bound(arr.begin(), arr.end(), target);
// 查找第一个>target的位置
auto it = upper_bound(arr.begin(), arr.end(), target);`
      },
      {
        name: '并查集',
        desc: '路径压缩+按秩合并',
        code: `class UnionFind {
    vector<int> parent, rank;
public:
    UnionFind(int n) : parent(n), rank(n, 0) {
        for (int i = 0; i < n; i++) parent[i] = i;
    }
    
    int find(int x) {
        if (parent[x] != x) parent[x] = find(parent[x]);
        return parent[x];
    }
    
    void unite(int x, int y) {
        int px = find(x), py = find(y);
        if (px == py) return;
        if (rank[px] < rank[py]) swap(px, py);
        parent[py] = px;
        if (rank[px] == rank[py]) rank[px]++;
    }
};`
      },
      {
        name: 'DFS模板',
        desc: '深度优先搜索',
        code: `void dfs(int u, vector<vector<int>>& graph, vector<bool>& visited) {
    visited[u] = true;
    // 处理当前节点
    
    for (int v : graph[u]) {
        if (!visited[v]) {
            dfs(v, graph, visited);
        }
    }
}`
      },
      {
        name: 'BFS模板',
        desc: '广度优先搜索',
        code: `void bfs(int start, vector<vector<int>>& graph) {
    queue<int> q;
    vector<bool> visited(graph.size(), false);
    q.push(start);
    visited[start] = true;
    
    while (!q.empty()) {
        int u = q.front();
        q.pop();
        // 处理当前节点
        
        for (int v : graph[u]) {
            if (!visited[v]) {
                visited[v] = true;
                q.push(v);
            }
        }
    }
}`
      },
      {
        name: '动态规划',
        desc: '一维DP数组',
        code: `vector<int> dp(n + 1, 0);
dp[0] = 1;  // 初始状态

for (int i = 1; i <= n; i++) {
    // 状态转移
    dp[i] = dp[i-1] + ...;
}`
      }
    ],
    PYTHON: [
      {
        name: '快速输入',
        desc: '读取多行输入',
        code: `import sys
input = sys.stdin.readline

n = int(input())
arr = list(map(int, input().split()))`
      },
      {
        name: '列表推导式',
        desc: '高效创建列表',
        code: `# 创建二维数组
dp = [[0] * m for _ in range(n)]

# 过滤列表
even_nums = [x for x in arr if x % 2 == 0]`
      },
      {
        name: '字典计数',
        desc: '使用Counter统计',
        code: `from collections import Counter

freq = Counter(arr)
most_common = freq.most_common(1)[0]`
      },
      {
        name: '二分查找',
        desc: 'bisect模块',
        code: `from bisect import bisect_left, bisect_right

idx = bisect_left(arr, target)  # 第一个>=target的位置
idx = bisect_right(arr, target)  # 第一个>target的位置`
      },
      {
        name: 'DFS模板',
        desc: '深度优先搜索',
        code: `def dfs(u, graph, visited):
    visited[u] = True
    # 处理当前节点
    
    for v in graph[u]:
        if not visited[v]:
            dfs(v, graph, visited)`
      },
      {
        name: 'BFS模板',
        desc: '广度优先搜索',
        code: `from collections import deque

def bfs(start, graph):
    queue = deque([start])
    visited = {start}
    
    while queue:
        u = queue.popleft()
        # 处理当前节点
        
        for v in graph[u]:
            if v not in visited:
                visited.add(v)
                queue.append(v)`
      }
    ],
    JAVA: [
      {
        name: '快速输入',
        desc: 'BufferedReader加速',
        code: `BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
String line = br.readLine();
String[] parts = line.split(" ");
int a = Integer.parseInt(parts[0]);`
      },
      {
        name: 'ArrayList操作',
        desc: '动态数组',
        code: `List<Integer> list = new ArrayList<>();
list.add(1);
list.remove(0);
Collections.sort(list);`
      },
      {
        name: 'HashMap操作',
        desc: '哈希表',
        code: `Map<String, Integer> map = new HashMap<>();
map.put("key", 1);
int value = map.getOrDefault("key", 0);`
      },
      {
        name: '优先队列',
        desc: '最小堆/最大堆',
        code: `// 最小堆
PriorityQueue<Integer> minHeap = new PriorityQueue<>();
// 最大堆
PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Collections.reverseOrder());`
      }
    ],
    C: [
      {
        name: '数组输入',
        desc: '读取数组',
        code: `int n;
scanf("%d", &n);
int arr[1000];
for (int i = 0; i < n; i++) {
    scanf("%d", &arr[i]);
}`
      },
      {
        name: '快速排序',
        desc: 'qsort函数',
        code: `int cmp(const void *a, const void *b) {
    return (*(int*)a - *(int*)b);
}

qsort(arr, n, sizeof(int), cmp);`
      }
    ]
  }

  function getBasicTemplate(language) {
    return basicTemplates[language] || basicTemplates.CPP
  }

  function getSnippets(language) {
    return snippets[language] || []
  }

  return {
    getBasicTemplate,
    getSnippets
  }
}
