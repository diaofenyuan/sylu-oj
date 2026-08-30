// 良性功能样例：A+B（Java，BufferedReader 读 stdin）。
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

public class APlusB {
    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String[] parts = in.readLine().trim().split("\\s+");
        long a = Long.parseLong(parts[0]);
        long b = Long.parseLong(parts[1]);
        System.out.println(a + b);
    }
}
