public class Test {

    public int dum;
    private final String test;

    public Test(String test) {
        this.test = test;
    }

    public final String getTest() {
        return test;
    }

    public final String getTest2() {
        return test + "2";
    }
}
