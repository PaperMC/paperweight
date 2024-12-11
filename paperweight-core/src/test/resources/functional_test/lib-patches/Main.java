import org.alcibiade.asciiart.coord.TextBoxSize;
import org.alcibiade.asciiart.image.rasterize.ShapeRasterizer;
import org.alcibiade.asciiart.raster.ExtensibleCharacterRaster;
import org.alcibiade.asciiart.raster.Raster;
import org.alcibiade.asciiart.raster.RasterContext;
import org.alcibiade.asciiart.widget.PictureWidget;
import org.alcibiade.asciiart.widget.TextWidget;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

public class Main {

    public static void main(String[] args) throws IOException, URISyntaxException {
        BufferedImage circleImage = ImageIO.read(new URI("https://raw.githubusercontent.com/PaperMC/docs/refs/heads/main/static/img/paper.png").toURL());

        TextWidget widget = new PictureWidget(new TextBoxSize(100, 30),
                circleImage, new ShapeRasterizer());
        Raster raster = new ExtensibleCharacterRaster();

        widget.render(new RasterContext(raster));
        System.out.println(raster);
    }
}
