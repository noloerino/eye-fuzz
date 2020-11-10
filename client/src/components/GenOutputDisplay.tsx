import m, {Vnode} from "mithril";
// Necessary to prevent mithril from getting dead code eliminated...
const _m = m;
import {MithrilTsxComponent} from "mithril-tsx-component";

type GenOutputDisplayAttrs = {
    currOutput: string;
    prevOutput: string;
    ago: number;
};

const GEN_CELL_STYLE = {
    maxHeight: "20em",
    maxWidth: "30em",
    whiteSpace: "pre-wrap",
    fontSize: 14,
    fontFamily: '"PT Mono", "Courier"'
};

export class GenOutputDisplay extends MithrilTsxComponent<GenOutputDisplayAttrs> {
    view(vnode: Vnode<GenOutputDisplayAttrs, this>) {
        return (
            <>
                <table>
                    <thead>
                    <tr>
                        <th scope="col">Current Generator Output</th>
                    </tr>
                    </thead>
                    <tbody>
                    <tr>
                        <td style={GEN_CELL_STYLE}>
                            {vnode.attrs.currOutput}
                        </td>
                    </tr>
                    </tbody>
                </table>
                <table>
                    <thead>
                    <tr>
                        <th scope="col">Generator Output {vnode.attrs.ago} Run(s) Ago</th>
                    </tr>
                    </thead>
                    <tbody>
                    <tr>
                        <td style={GEN_CELL_STYLE}>
                            {vnode.attrs.prevOutput}
                        </td>
                    </tr>
                    </tbody>
                </table>
            </>
        );
    }
}
