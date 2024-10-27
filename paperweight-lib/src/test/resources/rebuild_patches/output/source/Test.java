public class Test {

    public int dum;
    public String test;

    public Test(String test) {
        this.test = test;
    }

    private final String getTest() {
        return test + "Test"; // Test
    }
}
