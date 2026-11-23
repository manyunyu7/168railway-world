//! Display names for stops in an existing Studio save. Virtual bus stops have no name field.
use super::*;
use std::sync::OnceLock;
use trainworld_sim::track::frame::great_circle;

type Point = (f64, f64);
struct Place {
    at: Point,
    name: &'static str,
}

fn distance(a: Point, b: Point) -> f64 {
    great_circle(a.0, a.1, b.0, b.1)
}

fn corridor(first: Point, last: Point) -> Option<&'static [Place]> {
    static CATALOG: OnceLock<Vec<Vec<Place>>> = OnceLock::new();
    let catalog = CATALOG.get_or_init(|| {
        let mut routes: Vec<Vec<Place>> = (0..14).map(|_| Vec::new()).collect();
        for row in include_str!("../../reference/transjakarta-stops.tsv")
            .lines()
            .filter(|l| !l.starts_with('#'))
        {
            let mut fields = row.splitn(4, '\t');
            let id: usize = fields.next().unwrap().parse().unwrap();
            let lon = fields.next().unwrap().parse().unwrap();
            let lat = fields.next().unwrap().parse().unwrap();
            routes[id - 1].push(Place {
                at: (lon, lat),
                name: fields.next().unwrap_or(""),
            });
        }
        routes
    });
    // Both termini must match the original corridor, even after the player renames a line.
    catalog.iter().find_map(|route| {
        let a = route.first()?.at;
        let b = route.last()?.at;
        ((distance(first, a) <= 125. && distance(last, b) <= 125.)
            || (distance(first, b) <= 125. && distance(last, a) <= 125.))
            .then_some(route.as_slice())
    })
}

pub(super) struct Labels {
    pub stops: Vec<String>,
    pub directions: Vec<String>,
    pub unnamed: usize,
}

pub(super) fn labels(api: &TrackApi, id: u32) -> Labels {
    let net = &api.world().net;
    let frame = net.frame();
    let line = &api.world().svc.lines[id as usize];
    let ids = api.line_stops(id);
    let bus = api.is_bus(id);
    let plan = line.bus.as_ref().filter(|_| bus);
    let places = plan.and_then(|p| {
        let a = p.stops.first()?;
        let b = p.stops.last()?;
        corridor(frame.to_lonlat(a.x, a.y), frame.to_lonlat(b.x, b.y))
    });
    let fallback = if bus {
        "Halte"
    } else if line.ferry {
        "Dermaga"
    } else {
        "Stasiun"
    };
    let mut unnamed = 0;
    let stops: Vec<_> = ids
        .iter()
        .enumerate()
        .map(|(i, &node)| {
            let stop = plan.and_then(|p| p.stops.get(i));
            let named_node = if bus {
                stop.and_then(|s| s.station)
            } else {
                Some(node)
            };
            if let Some(name) = named_node
                .map(|n| api.node_name(n))
                .filter(|name| !name.trim().is_empty())
            {
                return name;
            }
            if let (Some(s), Some(places)) = (stop, places) {
                let point = frame.to_lonlat(s.x, s.y);
                let closest = places
                    .iter()
                    .map(|p| (distance(p.at, point), p))
                    .filter(|(metres, _)| *metres <= 85.)
                    .min_by(|a, b| a.0.total_cmp(&b.0));
                if let Some((_, p)) = closest.filter(|(_, p)| !p.name.is_empty()) {
                    return p.name.to_owned();
                }
            }
            unnamed += 1;
            format!("{fallback} {}", i + 1)
        })
        .collect();
    let mut directions = Vec::new();
    if let (Some(first), Some(last)) = (stops.first(), stops.last()) {
        directions.push(last.clone());
        if !line.one_way {
            directions.push(first.clone());
        }
    }
    Labels {
        stops,
        directions,
        unnamed,
    }
}
