public class Test {

    public int dum;
    public String test;// Paper-AT: public-f test

    public Test(String test) {
        this.test = test;
    }

    private final String getTest() {// Paper-AT: private+f getTest()Ljava/lang/String;
        return test + "Test"; // Test
    }
}
